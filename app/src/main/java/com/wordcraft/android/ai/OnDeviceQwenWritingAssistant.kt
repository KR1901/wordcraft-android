package com.wordcraft.android.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Placeholder writing assistant while LiteRT-LM is kept out of the APK.
 * Native LiteRT packages were causing immediate process death on some Android 15/16 devices.
 * Download/status still work via [QwenModelStore]; inference will return a clear error until
 * a safer optional integration ships.
 */
class OnDeviceQwenWritingAssistant(private val context: Context) : WritingAssistant {
    override suspend fun rewrite(input: String, action: WritingAction): WritingResult =
        withContext(Dispatchers.IO) {
            check(QwenModelStore.isModelDownloaded(context)) {
                "Download the Qwen model in AI settings first. On-device inference is temporarily unavailable in this build while we fix Android 16 compatibility."
            }
            throw IllegalStateException(
                "On-device Qwen inference is temporarily disabled in this build (Android 16 stability). " +
                    "The writing studio, keyboard, and model download still work. Update when LiteRT is re-enabled."
            )
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
