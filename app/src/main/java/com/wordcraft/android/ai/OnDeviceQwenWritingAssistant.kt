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
 * Fully on-device writing assistant backed by the Apache-2.0 Qwen3 0.6B INT4
 * LiteRT-LM artifact. The model is downloaded separately so it does not inflate
 * the APK by hundreds of megabytes.
 *
 * Do not call this class from UI composition startup paths. Use [QwenModelStore]
 * for download/status checks so opening the app does not load native libraries.
 */
class OnDeviceQwenWritingAssistant(private val context: Context) : WritingAssistant {
    override suspend fun rewrite(input: String, action: WritingAction): WritingResult =
        withContext(Dispatchers.IO) {
            val model = QwenModelStore.modelFile(context)
            check(QwenModelStore.isModelDownloaded(context)) {
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
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            "You are WordCraft, a concise writing assistant. Follow the requested " +
                                "writing task. Return only the improved text, without explanations, " +
                                "quotes, or a preamble. Preserve the author's meaning and language."
                        )
                    )
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

    companion object {
        const val MODEL_NAME = QwenModelStore.MODEL_NAME
        const val MODEL_SIZE_LABEL = QwenModelStore.MODEL_SIZE_LABEL
        const val MODEL_URL = QwenModelStore.MODEL_URL

        fun modelFile(context: Context) = QwenModelStore.modelFile(context)

        fun isModelDownloaded(context: Context) = QwenModelStore.isModelDownloaded(context)

        suspend fun downloadModel(
            context: Context,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
        ) = QwenModelStore.downloadModel(context, onProgress)
    }
}
