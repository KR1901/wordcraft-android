package com.wordcraft.android.ai

import android.content.Context

/**
 * Public entry point for on-device rewriting.
 *
 * This class deliberately has **no** LiteRT imports and does no work at construction time, so
 * the app can reference it from the UI (and from status checks) without ever loading native
 * code. The real runtime lives in [LiteRtWritingAssistant], which is only touched inside
 * [rewrite]. Every failure — including a missing or unloadable native library, which arrives as
 * an [Error] rather than an [Exception] — is converted into a readable message.
 */
class OnDeviceQwenWritingAssistant(private val context: Context) : WritingAssistant {
    override suspend fun rewrite(input: String, action: WritingAction): WritingResult {
        check(QwenModelStore.isModelDownloaded(context)) {
            "Download the Qwen model in AI settings before using writing actions."
        }
        return try {
            LiteRtWritingAssistant(context).rewrite(input, action)
        } catch (error: Throwable) {
            throw IllegalStateException(describe(error), error)
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is UnsatisfiedLinkError, is NoClassDefFoundError, is ExceptionInInitializerError ->
            "The on-device model runtime could not be loaded on this device " +
                "(${error.javaClass.simpleName}). The writing studio and keyboard still work."
        is OutOfMemoryError ->
            "Not enough memory to run the model on this device. Close other apps and try again."
        else -> error.message ?: "On-device writing failed. Try again."
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
