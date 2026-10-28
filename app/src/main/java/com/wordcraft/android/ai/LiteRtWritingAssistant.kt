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
 * Prompting notes (this is where rewrite quality is won or lost on a 0.6B model):
 *  - The system prompt states the contract: always rewrite, never echo, output text only.
 *  - The user turn carries a style, the delimited text, and one worked example. Few-shot examples
 *    are the single biggest lever for a small instruction-following model.
 *  - Sampling is explicit: temperature 0.7 / top-p 0.95 / top-k 40. The runtime default behaves
 *    close to greedy, which is exactly how you get the input back with a comma added.
 *  - If the answer is still a near-copy, one retry at temperature 1.0 with an "insist" instruction.
 *    If that also fails we say so instead of pretending the text was improved.
 */
internal class LiteRtWritingAssistant(private val context: Context) {

    suspend fun rewrite(input: String, action: WritingAction): WritingResult =
        withContext(Dispatchers.IO) {
            val model = QwenModelStore.modelFile(context)
            check(model.isFile && model.length() > 0) {
                "Download the Qwen model in AI settings before using writing actions."
            }
            val trimmed = trimForContext(input)
            val engine = EngineCache.engineFor(context, model)

            // Grammar fixing may legitimately return correct text untouched; every other action
            // promises a visible change, so an echo there is a failure, not a result.
            val expectsChange = action != WritingAction.FIX_GRAMMAR

            var output = generate(engine, trimmed, action, insist = false)
            if (expectsChange && isEcho(trimmed, output)) {
                output = generate(engine, trimmed, action, insist = true)
            }
            check(output.isNotBlank()) { "The local model returned an empty response." }
            if (expectsChange) {
                check(!isEcho(trimmed, output)) {
                    "The on-device model could not restyle this text. Try a shorter sentence, or " +
                        "choose a different tone."
                }
            }
            WritingResult(output)
        }

    private suspend fun generate(engine: Engine, input: String, action: WritingAction, insist: Boolean): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction()),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = if (insist) 1.0 else 0.7, seed = 0)
        )
        engine.createConversation(config).use { conversation ->
            val response = conversation.sendMessageAsync(userPrompt(input, action, insist)).toList()
                .joinToString(separator = "") { it.toString() }
            return clean(response)
        }
    }

    private fun systemInstruction(): String = """
        You are a copy editor inside a phone keyboard. You always rewrite the text you are given.
        You never return it unchanged.

        Rules:
        - The rewrite must visibly match the requested style.
        - Keep the meaning, facts, names, numbers and language of the original.
        - Keep the length within about 25% of the original.
        - Reply with the rewritten text only. No quotes, labels, notes or explanation.
    """.trimIndent()

    private fun userPrompt(input: String, action: WritingAction, insist: Boolean): String = buildString {
        appendLine("STYLE: ${action.label}")
        appendLine("GOAL: ${action.instruction}")
        appendLine()
        appendLine("TEXT:")
        appendLine("<<<")
        appendLine(input)
        appendLine(">>>")
        appendLine()
        appendLine("WORKED EXAMPLE")
        appendLine("STYLE: Professional")
        appendLine("TEXT: <<< hey can u send me the file when u get a chance >>>")
        appendLine("REWRITE: Could you please send me the file when you get a chance?")
        appendLine()
        if (insist) {
            appendLine("Now rewrite TEXT in the STYLE above. Change the wording so the style is")
            appendLine("unmistakable. Do not repeat the original sentences.")
        } else {
            appendLine("Now rewrite TEXT in the STYLE above.")
        }
    }

    /** Strips the wrappers small models like to add: preambles, labels, quotes, thought channels. */
    private fun clean(raw: String): String {
        var text = raw.trim()
        if (text.contains("</thought>")) {
            text = text.substringAfterLast("</thought>").trim()
        }
        val prefixes = listOf(
            "REWRITE:", "Rewrite:", "Rewritten:", "Rewritten text:",
            "Output:", "Result:", "Sure, here's the rewritten text:", "Sure, here is the rewritten text:"
        )
        for (prefix in prefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim()
            }
        }
        text = text.removeSurrounding("\"").removeSurrounding("“", "”").trim()
        // Keep only the first paragraph: anything after a blank line is model chatter.
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

    /** The model has a small context; keep the tail, which is what the user is looking at. */
    private fun trimForContext(input: String): String {
        val clean = input.trim()
        if (clean.length <= MAX_INPUT_CHARS) return clean
        val tail = clean.takeLast(MAX_INPUT_CHARS)
        val firstBreak = tail.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
        return if (firstBreak in 1 until tail.length - 1) tail.substring(firstBreak + 1).trim() else tail
    }

    private companion object {
        const val MAX_INPUT_CHARS = 900
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
