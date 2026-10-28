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
            "Download a model in WordCraft settings before using writing actions."
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
            "Not enough memory for this model. Close other apps, or pick a smaller model in settings."
        else -> error.message ?: "On-device writing failed. Try again."
    }

    companion object {
        fun activeModel(context: Context): LocalModel = QwenModelStore.active(context)

        fun modelFile(context: Context) = QwenModelStore.modelFile(context)

        fun isModelDownloaded(context: Context) = QwenModelStore.isModelDownloaded(context)

        suspend fun downloadModel(
            context: Context,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
        ) = QwenModelStore.downloadModel(context, QwenModelStore.active(context), onProgress)
    }
}
