package com.wordcraft.android.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * File-only helpers for the on-device Qwen model.
 * Intentionally has **no** LiteRT / native imports so UI and status checks
 * never load native libraries at process start.
 */
object QwenModelStore {
    const val MODEL_NAME = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
    const val MODEL_SIZE_LABEL = "about 347 MB"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/$MODEL_NAME"
    private const val MIN_MODEL_BYTES = 100_000_000L

    fun modelFile(context: Context): File = File(context.filesDir, MODEL_NAME)

    fun isModelDownloaded(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() > MIN_MODEL_BYTES
    }

    suspend fun downloadModel(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val destination = modelFile(context)
        if (isModelDownloaded(context)) return@withContext destination

        val partial = File(context.filesDir, "$MODEL_NAME.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WordCraft-Android")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Model download failed (HTTP ${connection.responseCode}). Check your connection and try again."
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
            check(partial.length() > MIN_MODEL_BYTES) {
                "The downloaded model file is incomplete. Please try again."
            }
            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "Could not save the model to app storage." }
            destination
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
