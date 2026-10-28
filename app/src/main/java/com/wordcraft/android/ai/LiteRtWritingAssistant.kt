package com.wordcraft.android.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * The only class in the app that touches LiteRT-LM.
 *
 * Everything here is deliberately lazy: the native library is loaded when this class is first
 * instantiated — i.e. when the user actually runs a writing action — never while the app is
 * starting. Keep it that way. If a device cannot load the runtime, the failure surfaces as a
 * message in the writing studio instead of killing the process on launch.
 */
internal class LiteRtWritingAssistant(private val context: Context) {

    suspend fun rewrite(input: String, action: WritingAction): WritingResult =
        withContext(Dispatchers.IO) {
            val model = QwenModelStore.modelFile(context)
            check(model.isFile && model.length() > 0) {
                "Download the Qwen model in AI settings before using writing actions."
            }

            val config = EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            Engine(config).use { engine ->
                engine.initialize()
                engine.createConversation(
                    ConversationConfig(systemInstruction = Contents.of(SYSTEM_INSTRUCTION))
                ).use { conversation ->
                    val prompt = """
                        Task: ${action.instruction}

                        Text to edit:
                        $input

                        Return only the revised text.
                    """.trimIndent()
                    val response = conversation.sendMessageAsync(prompt).toList()
                        .joinToString(separator = "") { it.toString() }
                        .trim()
                    check(response.isNotBlank()) { "The local model returned an empty response." }
                    WritingResult(response)
                }
            }
        }

    private companion object {
        const val SYSTEM_INSTRUCTION =
            "You are WordCraft, a concise writing assistant. Follow the requested writing task. " +
                "Return only the improved text, without explanations, quotes, or a preamble. " +
                "Preserve the author's meaning and language."
    }
}
