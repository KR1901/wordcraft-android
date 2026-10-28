package com.wordcraft.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Explains why the previous run ended.
 *
 * Two independent sources are shown together, because either one alone can be useless:
 * our own uncaught-exception log carries the real stack trace but only exists if the handler
 * was installed, and the system's process-exit history always exists on Android 11+ but often
 * reports nothing more than "Description: crash".
 */
object CrashReport {
    private const val CRASH_FILE = "last_crash.txt"
    private const val DISMISSED_FILE = "last_crash_dismissed.txt"
    private const val ATTEMPTS_FILE = "studio_attempts.txt"
    private const val MAX_CHARS = 6000
    private const val MAX_EXIT_TRACE_CHARS = 2500

    data class Report(val text: String, val at: Long)

    fun record(context: Context, error: Throwable) {
        val stack = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }
        val text = buildString {
            appendLine("Thread: ${Thread.currentThread().name}")
            appendLine("When: ${System.currentTimeMillis()}")
            appendLine("Uncaught ${error.javaClass.name}: ${error.message}")
            appendLine()
            append(stack.toString())
        }
        runCatching { File(context.filesDir, CRASH_FILE).writeText(text.take(MAX_CHARS)) }
    }

    /** Everything known about the previous failure, or null when there is nothing new. */
    fun latest(context: Context): Report? {
        val dismissedAt = dismissedAt(context)
        val java = javaCrash(context)
        val system = systemExit(context)
        val newestAt = maxOf(java?.at ?: 0L, system?.at ?: 0L)
        if (newestAt == 0L || newestAt <= dismissedAt) return null
        val text = buildString {
            if (java != null) {
                appendLine("=== In-app handler (real stack trace) ===")
                appendLine(java.text)
                appendLine()
            }
            if (system != null) {
                appendLine("=== Android's process-exit record ===")
                appendLine(system.text)
            }
        }.trim()
        return Report(text.take(MAX_CHARS), newestAt)
    }

    fun hasJavaCrash(context: Context): Boolean = javaCrash(context) != null

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
        appendLine("In-app crash log present: ${hasJavaCrash(context)}")
    }.trimEnd()
}
