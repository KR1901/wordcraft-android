package com.wordcraft.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Deliberately boring launcher screen with **no** Compose, no androidx.startup, no native code
 * and no theme dependencies — just framework widgets.
 *
 * Normal launches forward straight to the writing studio. If the studio is opened and never
 * comes up (it crashed, or the process was killed before it could report), the next launch
 * lands here instead and shows whatever evidence exists: our uncaught-exception log, the
 * system's process-exit record (which covers native crashes), the launch counter, and the
 * device facts needed to reproduce it.
 */
class DiagnosticsActivity : Activity() {
    private var forwarded = false
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashReport.studioAttempts(this) == 0) {
            // Healthy state: go straight to the studio, exactly like a normal launcher.
            forwarded = true
            CrashReport.noteStudioStart(this)
            startActivity(studioIntent())
            finish()
            return
        }
        try {
            buildUi()
        } catch (error: Throwable) {
            setContentView(
                TextView(this).apply {
                    text = "WordCraft diagnostics failed to draw:\n\n$error"
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.WHITE)
                    setPadding(dp(16), dp(24), dp(16), dp(24))
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!forwarded && ::body.isInitialized) refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "WordCraft diagnostics"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "The writing studio was opened and did not come up. The details below are what " +
                "the system recorded. Nothing is uploaded anywhere — use Share to send it."
            setTextColor(Color.DKGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(12))
        })
        body = TextView(this).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(body)
        root.addView(Button(this).apply {
            text = "Open the writing studio"
            setOnClickListener {
                CrashReport.noteStudioStart(this@DiagnosticsActivity)
                startActivity(studioIntent())
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(Button(this).apply {
            text = "Share this report"
            setOnClickListener { share() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Reset and try the studio as a fresh install"
            setOnClickListener {
                CrashReport.resetStudioAttempts(this@DiagnosticsActivity)
                CrashReport.noteStudioStart(this@DiagnosticsActivity)
                startActivity(studioIntent())
            }
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        val report = CrashReport.latest(this)
        val attempts = CrashReport.studioAttempts(this)
        val explanation = when {
            report != null -> "Recorded failure:"
            attempts > 0 -> "No crash record was written, but the studio was opened " +
                "${attempts}x without ever reaching the screen. That points at the process being " +
                "killed before Android could record a reason — a missing or unmappable native " +
                "library is the usual cause."
            else -> "No failures recorded."
        }
        body.text = buildString {
            appendLine(CrashReport.deviceSummary(this@DiagnosticsActivity))
            appendLine()
            appendLine(explanation)
            appendLine()
            appendLine(report?.text ?: "(no stack trace available)")
        }
    }

    /** Carries the launch intent's extras (the keyboard handoff) through to the studio. */
    private fun studioIntent(): Intent =
        Intent(this, MainActivity::class.java).also { it.putExtras(intent) }

    private fun share() {
        val text = body.text.toString()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WordCraft crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share WordCraft report")) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
