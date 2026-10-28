package com.wordcraft.android.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Download and storage for the on-device models.
 *
 * File-only helpers: **no** LiteRT / native imports, so UI and status checks never load native
 * libraries at process start. Each model is a separate file in app-private storage, so several can
 * coexist and the user can switch between them without re-downloading.
 */
object QwenModelStore {
    private const val PREFS = "wordcraft_models"
    private const val KEY_ACTIVE = "active_model"
    private const val PROGRESS_INTERVAL_MS = 250L

    fun active(context: Context): LocalModel = LocalModel.fromId(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)
    )

    fun setActive(context: Context, model: LocalModel) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, model.id).apply()
    }

    fun modelFile(context: Context, model: LocalModel = active(context)): File =
        File(context.filesDir, model.fileName)

    fun isModelDownloaded(context: Context, model: LocalModel = active(context)): Boolean {
        val file = modelFile(context, model)
        return file.isFile && file.length() >= model.minimumBytes
    }

    fun downloadedModels(context: Context): List<LocalModel> =
        LocalModel.entries.filter { isModelDownloaded(context, it) }

    fun delete(context: Context, model: LocalModel) {
        runCatching { modelFile(context, model).delete() }
    }

    /**
     * Downloads [model] into app-private storage.
     *
     * [onProgress] is throttled to roughly four updates a second and is always invoked on the
     * main thread, so callers can write it straight into UI state.
     */
    suspend fun downloadModel(
        context: Context,
        model: LocalModel = active(context),
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val destination = modelFile(context, model)
        if (isModelDownloaded(context, model)) return@withContext destination

        val partial = File(context.filesDir, "${model.fileName}.part")
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WordCraft-Android")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Download failed (HTTP ${connection.responseCode}). Check your connection and try again."
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastReportedAt = 0L
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.nanoTime() / 1_000_000
                        if (now - lastReportedAt >= PROGRESS_INTERVAL_MS) {
                            lastReportedAt = now
                            publishProgress(onProgress, downloaded, total)
                        }
                    }
                }
            }
            check(downloaded >= model.minimumBytes) {
                "The downloaded file is incomplete (${downloaded / 1_000_000} MB of ${model.sizeLabel}). Please try again."
            }
            publishProgress(onProgress, downloaded, total)
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

    private suspend fun publishProgress(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        downloaded: Long,
        total: Long
    ) = withContext(Dispatchers.Main.immediate) { onProgress(downloaded, total) }
}
