package com.wordcraft.android.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * The only class in the app that touches LiteRT-LM.
 *
 * Everything here is deliberately lazy: the native library is loaded when this class is first
 * instantiated — i.e. when the user actually runs a writing action — never while the app is
 * starting. Keep it that way.
 *
 * ## The prompt shape is not a guess
 *
 * It was measured against the real model with the `litert-lm` Python runtime, using the exact text
 * that used to fail. What the measurement showed:
 *
 *  - Asking for `<answer>` tags does not work. These models ignore the tags, and because the parser
 *    then found no tags it returned the whole response — which contained the echoed prompt. That is
 *    the "it output my text back / it printed the instructions" bug.
 *  - Any instruction placed *after* the text, or any metadata like "this text has 8 sentences",
 *    gets echoed into the answer. Both are gone.
 *  - What does work: the instruction lives in the **system message** and the user turn is **only
 *    the text**. Measured output for a 698-character input: clean, all six paragraphs, comma and
 *    capitalisation fixed, 695 characters out.
 *
 * Sampling is explicit (temperature 0.3 for proofreading, 0.7 for restyling, top-p 0.95, top-k 40)
 * and thinking is disabled — Qwen3 and Qwen3.5 default to thinking-on, and the reasoning text ends
 * up in the answer.
 */
internal class LiteRtWritingAssistant(private val context: Context) {

    suspend fun rewrite(
        input: String,
        action: WritingAction,
        length: LengthTarget = LengthTarget.SAME
    ): WritingResult = withContext(Dispatchers.IO) {
        val engine = engine()
        val trimmed = trimForContext(input)
        if (action == WritingAction.FIX_GRAMMAR) {
            WritingResult(grammarFix(engine, trimmed))
        } else {
            WritingResult(toneRewrite(engine, trimmed, action, length))
        }
    }

    /** Free-form prompt for the chat screen. */
    suspend fun ask(prompt: String): WritingResult = withContext(Dispatchers.IO) {
        val answer = generate(engine(), CHAT_SYSTEM, prompt, temperature = 0.7)
        check(answer.isNotBlank()) { "The model returned an empty response." }
        WritingResult(answer)
    }

    // ------------------------------------------------------------------ grammar

    private suspend fun grammarFix(engine: Engine, input: String): String {
        var output = generate(engine, GRAMMAR_SYSTEM, input, temperature = 0.3)
        if (droppedContent(input, output)) {
            output = generate(engine, GRAMMAR_SYSTEM_STRICT, input, temperature = 0.3)
        }
        check(output.isNotBlank()) { "The model returned an empty response." }
        check(!droppedContent(input, output)) {
            "The model kept summarising instead of correcting (${input.length} → ${output.length} " +
                "characters). Try a shorter selection, or the larger model in settings."
        }
        return output
    }

    /** True when the answer lost content: much shorter, or fewer sentences than the input. */
    private fun droppedContent(input: String, output: String): Boolean {
        val inChars = input.count { !it.isWhitespace() }
        val outChars = output.count { !it.isWhitespace() }
        if (inChars == 0 || outChars == 0) return false
        val ratio = outChars.toDouble() / inChars
        val lostSentences = sentenceCount(output) < sentenceCount(input) - 1
        return ratio < 0.85 || lostSentences
    }

    // ------------------------------------------------------------------ tones

    private suspend fun toneRewrite(
        engine: Engine,
        input: String,
        action: WritingAction,
        length: LengthTarget
    ): String {
        var output = generate(engine, toneSystem(action, length, insist = false), input, temperature = 0.7)
        if (isEcho(input, output) || collapsed(input, output, length) || isFragment(input, output)) {
            output = generate(engine, toneSystem(action, length, insist = true), input, temperature = 1.0)
        }
        check(output.isNotBlank()) { "The model returned an empty response." }
        check(!isEcho(input, output)) {
            "The model could not restyle this text. Try a different tone, or the larger model in settings."
        }
        check(!isFragment(input, output)) {
            "The model returned only a fragment instead of a rewrite. Try again, or use a larger model."
        }
        return output
    }

    /** A one-word answer ("and") for a paragraph-long input is a failure, not a short rewrite. */
    private fun isFragment(input: String, output: String): Boolean =
        input.length > 40 && output.length < input.length / 5

    /** "Same length" is a promise too: a 70% collapse means the model summarised. */
    private fun collapsed(input: String, output: String, length: LengthTarget): Boolean {
        if (length != LengthTarget.SAME) return false
        val inChars = input.count { !it.isWhitespace() }
        val outChars = output.count { !it.isWhitespace() }
        if (inChars == 0) return false
        return outChars.toDouble() / inChars < 0.7
    }

    private fun toneSystem(action: WritingAction, length: LengthTarget, insist: Boolean): String =
        buildString {
            appendLine("Rewrite the text the user sends in a ${action.label} style.")
            appendLine("Goal: ${action.instruction}")
            appendLine("Length: ${length.instruction}")
            appendLine("Keep the meaning, facts, names, numbers and language.")
            if (insist) {
                appendLine("The wording must change — do not copy the original sentences.")
            }
            appendLine("Do not add commentary, headings or notes.")
            appendLine("Reply with the rewritten text only, with no preamble.")
        }

    // ------------------------------------------------------------------ generation

    private suspend fun generate(
        engine: Engine,
        system: String,
        user: String,
        temperature: Double
    ): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature, seed = 0),
            // Qwen3 and Qwen3.5 default to thinking-on and the reasoning text lands in the answer.
            thinkingConfig = ThinkingConfig(enableThinking = false)
        )
        engine.createConversation(config).use { conversation ->
            val response = conversation.sendMessageAsync(user).toList()
                .joinToString(separator = "") { it.toString() }
            return stripWrappers(response)
        }
    }

    /** Removes the wrappers models still add, and any echoed trailing instruction. */
    private fun stripWrappers(raw: String): String {
        var text = raw.trim()
        if (text.contains("</thought>")) text = text.substringAfterLast("</thought>").trim()
        for (label in LEADING_LABELS) {
            if (text.startsWith(label, ignoreCase = true)) text = text.substring(label.length).trim()
        }
        text = text.removeSurrounding("\"").removeSurrounding("“", "”").trim()
        // Defensive: some builds repeat the closing instruction back at the end.
        val cut = listOf("\nReply with", "\nReply with the", "\nDo not add", "\nRules:").firstOrNull {
            text.contains(it, ignoreCase = true)
        }
        if (cut != null) text = text.substringBefore(cut).trim()
        return text
    }

    /** True when the model handed the input straight back (with at most cosmetic edits). */
    private fun isEcho(original: String, output: String): Boolean {
        val a = original.filter { it.isLetterOrDigit() }.lowercase()
        val b = output.filter { it.isLetterOrDigit() }.lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val wordsA = original.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val wordsB = output.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size.toDouble() / maxOf(wordsA.size, wordsB.size)
        return overlap > 0.9 && abs(a.length - b.length) <= 4
    }

    private fun sentenceCount(text: String): Int =
        text.split(Regex("[.!?]+")).count { it.isNotBlank() }

    /** The models have a small context; keep the tail, which is what the user is looking at. */
    private fun trimForContext(input: String): String {
        val clean = input.trim()
        if (clean.length <= MAX_INPUT_CHARS) return clean
        val tail = clean.takeLast(MAX_INPUT_CHARS)
        val firstBreak = tail.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
        return if (firstBreak in 1 until tail.length - 1) tail.substring(firstBreak + 1).trim() else tail
    }

    private fun engine(): Engine {
        val model = QwenModelStore.modelFile(context)
        check(model.isFile && model.length() > 0) {
            "Download a model in WordCraft settings before using writing actions."
        }
        return EngineCache.engineFor(context, model)
    }

    private companion object {
        const val MAX_INPUT_CHARS = 1800

        val LEADING_LABELS = listOf(
            "Corrected text:", "Corrected:", "Corrected version:",
            "Rewritten text:", "Rewritten:", "Rewrite:", "Answer:", "Output:"
        )

        val GRAMMAR_SYSTEM = """
            You are a proofreader. Correct the spelling, grammar, punctuation and capitalisation of
            the text the user sends.
            Rules:
            - Keep every sentence, in the same order. Do not shorten, summarise, reorder or omit anything.
            - Keep the author's words wherever they are already correct.
            - Do not add commentary, headings or notes.
            - Reply with the corrected text only.
        """.trimIndent()

        val GRAMMAR_SYSTEM_STRICT = """
            You are a proofreader. Correct the spelling, grammar, punctuation and capitalisation of
            the text the user sends.
            Hard rules:
            - Return the complete text. Never summarise.
            - Every sentence of the input must appear in the output, in the same order.
            - If the input has ten sentences, your answer has ten sentences.
            - Do not add commentary, headings or notes.
            - Reply with the corrected text only.
        """.trimIndent()

        val CHAT_SYSTEM = """
            You are WordCraft, a concise writing assistant running on the user's phone.
            Answer the user's request directly and briefly. If they ask for text, give the text only,
            without preamble or commentary.
        """.trimIndent()
    }
}

/**
 * One Engine per model file, reused across rewrites. Creating an Engine loads the model; doing
 * that on every tap cost several seconds and made the keyboard feel broken.
 */
private object EngineCache {
    private var engine: Engine? = null
    private var path: String? = null

    @Synchronized
    fun engineFor(context: Context, model: File): Engine {
        val existing = engine
        if (existing != null && path == model.absolutePath) return existing
        runCatching { existing?.close() }
        val created = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
        )
        created.initialize()
        engine = created
        path = model.absolutePath
        return created
    }
}
