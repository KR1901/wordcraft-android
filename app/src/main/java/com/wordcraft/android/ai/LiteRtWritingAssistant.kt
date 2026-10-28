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
 * Prompting notes (this is where rewrite quality is won or lost on a small model):
 *  - The system prompt states the contract — always rewrite, never echo, answer inside
 *    `<answer>` tags — and carries one worked example.
 *  - The example lives in the **system** message, never in the user turn. Putting it in the user
 *    turn made the model continue the example instead of answering: it printed the sample input
 *    and the sample rewrite verbatim.
 *  - Sampling is explicit: temperature 0.7 / top-p 0.95 / top-k 40. The runtime default behaves
 *    close to greedy, which is how you get the input back with a comma moved.
 *  - If the answer is still a near-copy (or an echo of the example), one retry at temperature 1.0
 *    with the example removed and an explicit "change the wording" instruction.
 *  - If that also fails we say so instead of pretending the text was improved.
 */
internal class LiteRtWritingAssistant(private val context: Context) {

    suspend fun rewrite(input: String, action: WritingAction): WritingResult =
        withContext(Dispatchers.IO) {
            val model = QwenModelStore.modelFile(context)
            check(model.isFile && model.length() > 0) {
                "Download a model in WordCraft settings before using writing actions."
            }
            val trimmed = trimForContext(input)
            val engine = EngineCache.engineFor(context, model)

            // Grammar fixing may legitimately return correct text untouched; every other action
            // promises a visible change, so an echo there is a failure, not a result.
            val expectsChange = action != WritingAction.FIX_GRAMMAR

            var output = generate(engine, trimmed, action, insist = false, withExample = true)
            if (expectsChange && (isEcho(trimmed, output) || isExampleLeak(output))) {
                output = generate(engine, trimmed, action, insist = true, withExample = false)
            }
            check(output.isNotBlank()) { "The model returned an empty response." }
            if (expectsChange) {
                check(!isEcho(trimmed, output) && !isExampleLeak(output)) {
                    "The model could not restyle this text. Try a shorter sentence, a different " +
                        "tone, or a larger model in settings."
                }
            }
            WritingResult(output)
        }

    private suspend fun generate(
        engine: Engine,
        input: String,
        action: WritingAction,
        insist: Boolean,
        withExample: Boolean
    ): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction(withExample)),
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = if (insist) 1.0 else 0.7,
                seed = 0
            )
        )
        engine.createConversation(config).use { conversation ->
            val response = conversation.sendMessageAsync(userPrompt(input, action, insist)).toList()
                .joinToString(separator = "") { it.toString() }
            return clean(response)
        }
    }

    private fun systemInstruction(withExample: Boolean): String = buildString {
        appendLine("You are a copy editor inside a phone keyboard. You rewrite text on request.")
        appendLine("Rules:")
        appendLine("- Always rewrite. Never return the input unchanged.")
        appendLine("- The rewrite must clearly match the requested style.")
        appendLine("- Keep the meaning, facts, names, numbers and language.")
        appendLine("- Keep the length within about 25% of the original.")
        appendLine("- Put ONLY the rewritten text between <answer> and </answer>.")
        if (withExample) {
            appendLine()
            appendLine("Example —")
            appendLine("Style: Professional. Text: hey can u send the file")
            appendLine("Answer: <answer>Could you please send the file?</answer>")
        }
    }

    private fun userPrompt(input: String, action: WritingAction, insist: Boolean): String = buildString {
        appendLine("Style: ${action.label}")
        appendLine("Goal: ${action.instruction}")
        appendLine("Text: $input")
        appendLine()
        if (insist) {
            appendLine(
                "Rewrite the text above in the ${action.label} style. The wording must change — do " +
                    "not copy the original sentences. Reply with <answer> and </answer> only."
            )
        } else {
            appendLine(
                "Rewrite the text above in the ${action.label} style. " +
                    "Reply with <answer> and </answer> only."
            )
        }
    }

    /** Extracts the answer, tolerating the wrappers small models add. */
    private fun clean(raw: String): String {
        var text = raw.trim()
        val open = text.indexOf("<answer>", ignoreCase = true)
        if (open >= 0) {
            val start = open + "<answer>".length
            val close = text.indexOf("</answer>", start, ignoreCase = true)
            text = if (close > start) text.substring(start, close) else text.substring(start)
            return tidy(text)
        }
        if (text.contains("</thought>")) {
            text = text.substringAfterLast("</thought>")
        }
        // Last resort: the model continued the example pattern.
        val marker = listOf("REWRITE:", "Answer:").firstOrNull { text.contains(it, ignoreCase = true) }
        if (marker != null) {
            text = text.substringAfterLast(marker, "")
        }
        return tidy(text)
    }

    private fun tidy(input: String): String {
        var text = input.trim()
        for (prefix in listOf("Answer:", "REWRITE:", "Rewrite:", "Rewritten:")) {
            if (text.startsWith(prefix, ignoreCase = true)) text = text.substring(prefix.length).trim()
        }
        text = text.removeSurrounding("\"").removeSurrounding("“", "”").trim()
        text = text.removePrefix("<<<").removeSuffix(">>>").trim()
        val firstParagraph = text.split("\n\n").firstOrNull()?.trim().orEmpty()
        return if (firstParagraph.isNotEmpty()) firstParagraph else text
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

    /** The models have a small context; keep the tail, which is what the user is looking at. */
    private fun trimForContext(input: String): String {
        val clean = input.trim()
        if (clean.length <= MAX_INPUT_CHARS) return clean
        val tail = clean.takeLast(MAX_INPUT_CHARS)
        val firstBreak = tail.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
        return if (firstBreak in 1 until tail.length - 1) tail.substring(firstBreak + 1).trim() else tail
    }

    private companion object {
        const val MAX_INPUT_CHARS = 900
        const val EXAMPLE_INPUT = "hey can u send the file"
        const val EXAMPLE_ANSWER = "Could you please send the file?"
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
