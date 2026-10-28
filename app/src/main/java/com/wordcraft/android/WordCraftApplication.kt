package com.wordcraft.android

import android.app.Application

/**
 * Installs a last-gasp handler so a crash that happens before the UI is usable still leaves
 * a readable trace on the device (shown by [CrashReport] on the next launch).
 */
class WordCraftApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashReport.record(this, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
