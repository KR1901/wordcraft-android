package com.wordcraft.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Explains why the previous run ended, and whether the studio survived being opened.
 *
 * A startup crash is exactly the case where the user cannot reach any UI, so this records
 * three things: our own uncaught-exception log (written by [WordCraftApplication]), the
 * system's process-exit history (Android 11+, which also reports *native* crashes with the
 * tombstone trace), and a launch counter that proves the studio was opened and never came up
 * even when neither of the first two has anything to say.
 */
object CrashReport {
    private const val CRASH_FILE = "last_crash.txt"
    private const val DISMISSED_FILE = "last_crash_dismissed.txt"
    private const val ATTEMPTS_FILE = "studio_attempts.txt"
    private const val MAX_CHARS = 4000
    private const val MAX_EXIT_TRACE_CHARS = 2500

    data class Report(val text: String, val at: Long)

    fun record(context: Context, error: Throwable) {
        val stack = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }
        runCatching {
            File(context.filesDir, CRASH_FILE).writeText(
                "Uncaught ${error.javaClass.name}: ${error.message}\n\n${stack.toString().take(MAX_CHARS)}"
            )
        }
    }

    /** The most recent unreported crash, or null when there is nothing new to show. */
    fun latest(context: Context): Report? {
        val dismissedAt = dismissedAt(context)
        val candidates = buildList {
            javaCrash(context)?.let(::add)
            systemExit(context)?.let(::add)
        }
        val newest = candidates.filter { it.at > dismissedAt }.maxByOrNull { it.at } ?: return null
        return Report(newest.text.take(MAX_CHARS), newest.at)
    }

    fun dismiss(context: Context, report: Report) {
        runCatching { File(context.filesDir, DISMISSED_FILE).writeText(report.at.toString()) }
    }

    /** Called when the studio is about to be shown; proves the launch was attempted. */
    fun noteStudioStart(context: Context) {
        val attempts = studioAttempts(context) + 1
        runCatching { File(context.filesDir, ATTEMPTS_FILE).writeText(attempts.toString()) }
    }

    /** Called once the studio is actually on screen, so a healthy launch clears the counter. */
    fun noteStudioAlive(context: Context) {
        runCatching { File(context.filesDir, ATTEMPTS_FILE).writeText("0") }
    }

    fun resetStudioAttempts(context: Context) = noteStudioAlive(context)

    fun studioAttempts(context: Context): Int =
        runCatching { File(context.filesDir, ATTEMPTS_FILE).readText().trim().toInt() }.getOrDefault(0)

    private fun dismissedAt(context: Context): Long =
        runCatching { File(context.filesDir, DISMISSED_FILE).readText().trim().toLong() }.getOrDefault(0L)

    private fun javaCrash(context: Context): Report? {
        val file = File(context.filesDir, CRASH_FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) return null
        return Report(text, file.lastModified())
    }

    private fun systemExit(context: Context): Report? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull().orEmpty()
        val crash = exits.firstOrNull {
            it.reason == ApplicationExitInfo.REASON_CRASH ||
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                it.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
        } ?: return null
        val kind = when (crash.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                "native crash (SIGSEGV/SIGABRT, or a library that failed to load)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "app initialisation failure"
            else -> "Java crash"
        }
        val trace = runCatching {
            crash.traceInputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrNull().orEmpty()
        val text = buildString {
            appendLine("Android reported a $kind at ${crash.timestamp}.")
            appendLine("Description: ${crash.description ?: "none"}")
            if (trace.isNotBlank()) {
                appendLine()
                appendLine(trace.take(MAX_EXIT_TRACE_CHARS))
            }
        }
        return Report(text.trim(), crash.timestamp)
    }

    /** Device facts that make a report actionable without asking a second round of questions. */
    fun deviceSummary(context: Context): String = buildString {
        appendLine("Build: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Studio launches that never came up: ${studioAttempts(context)}")
    }.trimEnd()
}
