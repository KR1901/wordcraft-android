package com.wordcraft.android.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The only class in the app that touches LiteRT-LM.
 *
 * Everything here is deliberately lazy: the native library is loaded when this class is first
 * instantiated — i.e. when the user actually runs a writing action — never while the app is
 * starting. Keep it that way.
 *
 * ## Grammar is not a rewrite
 *
 * Asking a 0.6–2B model to "return the corrected text" fails: it copies the input, or summarises
 * it, or repeats a paragraph. Measured against the real model, so instead of regenerating the text
 * the model is asked to **list edits**:
 *
 *     [{"find": "exact span", "replace": "corrected span"}, …]
 *
 * and the app applies those spans to the original. Content cannot be lost, nothing can be
 * summarised, an empty list honestly means "no issues found", and a garbled answer can only fail to
 * apply rather than corrupt the text. Verified on a real model: it returned valid spans
 * ("carry the burden", "at risk", "we are completely wrong") for the 698-character test text.
 *
 * ## Tone work
 *
 * Instruction in the system message, the user turn is **only the text** — anything else gets echoed
 * back. Repetition penalties are on, because these models fall into "same paragraph three times"
 * loops, and a degeneracy check rejects the result if that happens anyway.
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
            grammarFix(engine, trimmed)
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

    private suspend fun grammarFix(engine: Engine, input: String): WritingResult {
        var raw = generate(engine, GRAMMAR_EDITS_SYSTEM, input, temperature = 0.2)
        var edits = GrammarEdits.parse(raw)
        if (edits.isEmpty() && !raw.contains("[")) {
            // No JSON at all: one retry with the stricter wording before concluding "no issues".
            raw = generate(engine, GRAMMAR_EDITS_SYSTEM_STRICT, input, temperature = 0.2)
            edits = GrammarEdits.parse(raw)
        }
        val corrected = GrammarEdits.apply(input, edits)
        val explanation = if (edits.isEmpty()) {
            "No grammar issues found"
        } else {
            "${edits.size} correction${if (edits.size == 1) "" else "s"}"
        }
        return WritingResult(corrected, explanation)
    }

    // ------------------------------------------------------------------ tones

    private suspend fun toneRewrite(
        engine: Engine,
        input: String,
        action: WritingAction,
        length: LengthTarget
    ): String {
        var output = generate(engine, toneSystem(action, length, insist = false), input, temperature = 0.7)
        if (RewriteQuality.isEcho(input, output) || RewriteQuality.isDegenerate(output) ||
            RewriteQuality.isCollapsed(input, output, length == LengthTarget.SAME) ||
            RewriteQuality.isFragment(input, output)
        ) {
            output = generate(engine, toneSystem(action, length, insist = true), input, temperature = 0.9)
        }
        check(output.isNotBlank()) { "The model returned an empty response." }
        check(!RewriteQuality.isEcho(input, output)) {
            "The model copied the text instead of restyling it. Try a different tone, or the larger model."
        }
        check(!RewriteQuality.isDegenerate(output)) {
            "The model started repeating itself. Try again, or switch to the larger model."
        }
        check(!RewriteQuality.isFragment(input, output)) {
            "The model returned only a fragment instead of a rewrite. Try again, or use the larger model."
        }
        return output
    }

    private fun toneSystem(action: WritingAction, length: LengthTarget, insist: Boolean): String =
        buildString {
            appendLine("Rewrite the text the user sends in a ${action.label} style.")
            appendLine("Goal: ${action.instruction}")
            appendLine("Length: ${length.instruction}")
            appendLine("Keep the meaning, facts, names, numbers and language.")
            if (insist) {
                appendLine("The wording must change — do not copy the original sentences.")
                appendLine("Do not repeat any sentence. Do not loop.")
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
            val response = conversation.sendMessageAsync(
                user,
                repetitionPenaltyConfig = RepetitionPenaltyConfig(
                    repetitionPenalty = 1.1f,
                    presencePenalty = 0f,
                    frequencyPenalty = 0f,
                    windowSize = 128
                ),
                noRepeatNgramConfig = NoRepeatNgramConfig(noRepeatNgramSize = 4, windowSize = 128)
            ).toList().joinToString(separator = "") { it.toString() }
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
        val cut = listOf("\nReply with", "\nDo not add", "\nRules:", "\nNote:").firstOrNull {
            text.contains(it, ignoreCase = true)
        }
        if (cut != null) text = text.substringBefore(cut).trim()
        return text
    }

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

        val GRAMMAR_EDITS_SYSTEM = """
            You are a proofreader. List the grammar, spelling, punctuation and capitalisation errors
            in the text the user sends.
            Output a JSON array only. Each item is an object with two keys:
              "find": an exact substring copied from the input, unchanged
              "replace": the corrected version of that substring
            Only include real errors. Do not rewrite for style. If there are no errors, output [].
        """.trimIndent()

        val GRAMMAR_EDITS_SYSTEM_STRICT = """
            You are a proofreader. Find the grammar, spelling, punctuation and capitalisation errors
            in the text the user sends.
            Reply with a JSON array and nothing else. Each item: {"find": "...", "replace": "..."}.
            "find" must be copied exactly from the input. If there are no errors, reply [].
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
