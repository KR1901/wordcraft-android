package com.wordcraft.android.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
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
 * Prompting rules learned the hard way:
 *  - The worked example belongs in the **system** message. In the user turn the model continues
 *    the example instead of answering it.
 *  - Sampling is explicit (temperature 0.7 / top-p 0.95 / top-k 40). The runtime default is
 *    near-greedy, which is how you get the input back with a comma moved.
 *  - **Fix grammar is a different task from rewriting.** Small models summarise it: the prompt
 *    forbids it, and a length/sentence guard rejects any answer that drops content, retries once,
 *    and otherwise fails loudly rather than silently returning a shortened text.
 *  - Tone actions honour a length target, and "same length" is guarded too.
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
        val engine = engine()
        val answer = generate(
            engine = engine,
            system = CHAT_SYSTEM,
            user = prompt,
            temperature = 0.7,
            extractAnswerTags = false
        )
        check(answer.isNotBlank()) { "The model returned an empty response." }
        WritingResult(answer)
    }

    // ------------------------------------------------------------------ grammar

    private suspend fun grammarFix(engine: Engine, input: String): String {
        val sentences = sentenceCount(input)
        val characters = input.count { !it.isWhitespace() }
        var output = generate(
            engine = engine,
            system = GRAMMAR_SYSTEM,
            user = grammarPrompt(input, sentences, characters, insist = false),
            temperature = 0.5,
            extractAnswerTags = true
        )
        if (droppedContent(input, output)) {
            output = generate(
                engine = engine,
                system = GRAMMAR_SYSTEM,
                user = grammarPrompt(input, sentences, characters, insist = true),
                temperature = 0.5,
                extractAnswerTags = true
            )
        }
        check(output.isNotBlank()) { "The model returned an empty response." }
        check(!droppedContent(input, output)) {
            "The model kept summarising instead of correcting (${input.length} → ${output.length} " +
                "characters). Try a shorter selection, or a larger model in settings."
        }
        return output
    }

    private fun grammarPrompt(input: String, sentences: Int, characters: Int, insist: Boolean): String =
        buildString {
            appendLine("Correct the spelling, grammar, punctuation and capitalisation of the text below.")
            appendLine("The text has $sentences sentences and about $characters characters.")
            appendLine("Your correction must also have $sentences sentences and about $characters characters.")
            if (insist) {
                appendLine("Do NOT shorten. Do NOT summarise. Do NOT drop any sentence. Keep every idea.")
            }
            appendLine()
            appendLine("Text: $input")
            appendLine()
            appendLine("Reply with <answer> and </answer> only.")
        }

    /** True when the answer lost content: shorter text, or fewer sentences than the input. */
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
        var output = generate(
            engine = engine,
            system = toneSystem(withExample = true, length = length),
            user = tonePrompt(input, action, length, insist = false),
            temperature = 0.7,
            extractAnswerTags = true
        )
        if (isEcho(input, output) || isExampleLeak(output) || collapsedForSameLength(input, output, length)) {
            output = generate(
                engine = engine,
                system = toneSystem(withExample = false, length = length),
                user = tonePrompt(input, action, length, insist = true),
                temperature = 1.0,
                extractAnswerTags = true
            )
        }
        check(output.isNotBlank()) { "The model returned an empty response." }
        check(!isEcho(input, output) && !isExampleLeak(output)) {
            "The model could not restyle this text. Try a shorter sentence, a different tone, or a " +
                "larger model in settings."
        }
        return output
    }

    private fun toneSystem(withExample: Boolean, length: LengthTarget): String = buildString {
        appendLine("You are a copy editor inside a phone keyboard. You rewrite text on request.")
        appendLine("Rules:")
        appendLine("- Always rewrite. Never return the input unchanged.")
        appendLine("- The rewrite must clearly match the requested style.")
        appendLine("- Keep the meaning, facts, names, numbers and language.")
        appendLine("- Length: ${length.instruction}")
        appendLine("- Put ONLY the rewritten text between <answer> and </answer>.")
        if (withExample) {
            appendLine()
            appendLine("Example —")
            appendLine("Style: Professional. Text: hey can u send the file")
            appendLine("Answer: <answer>Could you please send the file?</answer>")
        }
    }

    private fun tonePrompt(
        input: String,
        action: WritingAction,
        length: LengthTarget,
        insist: Boolean
    ): String = buildString {
        appendLine("Style: ${action.label}")
        appendLine("Goal: ${action.instruction}")
        appendLine("Length: ${length.label} — ${length.instruction}")
        appendLine("Text: $input")
        appendLine()
        if (insist) {
            appendLine(
                "Rewrite the text above in the ${action.label} style. The wording must change — do " +
                    "not copy the original sentences. Reply with <answer> and </answer> only."
            )
        } else {
            appendLine("Rewrite the text above in the ${action.label} style. Reply with <answer> and </answer> only.")
        }
    }

    /** "Same length" is a promise too: a 70% collapse means the model summarised. */
    private fun collapsedForSameLength(input: String, output: String, length: LengthTarget): Boolean {
        if (length != LengthTarget.SAME) return false
        val inChars = input.count { !it.isWhitespace() }
        val outChars = output.count { !it.isWhitespace() }
        if (inChars == 0) return false
        return outChars.toDouble() / inChars < 0.7
    }

    // ------------------------------------------------------------------ generation

    private suspend fun generate(
        engine: Engine,
        system: String,
        user: String,
        temperature: Double,
        extractAnswerTags: Boolean
    ): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature, seed = 0)
        )
        engine.createConversation(config).use { conversation ->
            val response = conversation.sendMessageAsync(user).toList()
                .joinToString(separator = "") { it.toString() }
            return if (extractAnswerTags) extractAnswer(response) else stripWrappers(response)
        }
    }

    /** Extracts the `<answer>` block, tolerating the wrappers small models add. */
    private fun extractAnswer(raw: String): String {
        var text = raw.trim()
        if (text.contains("</thought>")) text = text.substringAfterLast("</thought>").trim()
        val open = text.indexOf("<answer>", ignoreCase = true)
        if (open >= 0) {
            val start = open + "<answer>".length
            val close = text.indexOf("</answer>", start, ignoreCase = true)
            text = if (close > start) text.substring(start, close) else text.substring(start)
            return tidy(text)
        }
        val marker = listOf("REWRITE:", "Answer:").firstOrNull { text.contains(it, ignoreCase = true) }
        if (marker != null) text = text.substringAfterLast(marker, "")
        return tidy(text)
    }

    private fun stripWrappers(raw: String): String {
        var text = raw.trim()
        if (text.contains("</thought>")) text = text.substringAfterLast("</thought>").trim()
        return tidy(text)
    }

    private fun tidy(input: String): String {
        var text = input.trim()
        for (prefix in listOf("Answer:", "REWRITE:", "Rewrite:", "Rewritten:")) {
            if (text.startsWith(prefix, ignoreCase = true)) text = text.substring(prefix.length).trim()
        }
        text = text.removeSurrounding("\"").removeSurrounding("“", "”").trim()
        text = text.removePrefix("<<<").removeSuffix(">>>").trim()
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

    /** True when the model repeated the worked example instead of rewriting the user's text. */
    private fun isExampleLeak(output: String): Boolean {
        val text = output.lowercase()
        return text.contains(EXAMPLE_INPUT) ||
            text.replace(Regex("[^a-z]"), "") == EXAMPLE_ANSWER.replace(Regex("[^a-z]"), "")
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
        const val EXAMPLE_INPUT = "hey can u send the file"
        const val EXAMPLE_ANSWER = "Could you please send the file?"

        val GRAMMAR_SYSTEM = """
            You are a proofreader. You correct spelling, grammar, punctuation and capitalisation.
            Hard rules:
            - Never shorten, summarise, reorder or omit anything.
            - Every sentence of the input must appear in the output, in the same order.
            - Keep the author's words wherever they are already correct.
            - Do not add new ideas, comments or examples.
            - Put ONLY the corrected text between <answer> and </answer>.
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
