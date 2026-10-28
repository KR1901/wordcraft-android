package com.wordcraft.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Deliberately boring launcher screen with **no** Compose, no androidx.startup, no native code
 * and no theme dependencies — just framework widgets.
 *
 * The screen is always drawn first, and only then does it hand over to the writing studio. That
 * ordering matters: if loading the studio's classes fails, the failure is displayed here instead
 * of the process dying before anything is on screen. Once a crash has been recorded it stops
 * handing over automatically, so a crash loop cannot hide the report.
 */
class DiagnosticsActivity : Activity() {
    private var forwarded = false
    private var lastError: Throwable? = null
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (error: Throwable) {
            showFallback(error)
            return
        }
        val healthy = CrashReport.studioAttempts(this) == 0 && CrashReport.latest(this) == null
        if (healthy) {
            // Healthy state: draw this screen, then get out of the way like a normal launcher.
            Handler(Looper.getMainLooper()).postDelayed({ forwardToStudio() }, 500)
        }
    }

    private fun forwardToStudio() {
        if (isFinishing || isDestroyed) return
        try {
            CrashReport.noteStudioStart(this)
            forwarded = true
            startActivity(studioIntent())
            finish()
        } catch (error: Throwable) {
            // Most often NoClassDefFoundError / NoSuchMethodError from the studio's own class
            // graph: exactly the failure that is invisible when the launcher *is* the studio.
            forwarded = false
            lastError = error
            refresh()
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
            text = "This screen is drawn before the writing studio starts, so it can report a " +
                "failure that would otherwise leave a blank screen. Nothing is uploaded — use " +
                "Share to send it."
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
            setOnClickListener { forwardToStudio() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        root.addView(Button(this).apply {
            text = "Dismiss report and continue"
            setOnClickListener {
                CrashReport.latest(this@DiagnosticsActivity)?.let { CrashReport.dismiss(this@DiagnosticsActivity, it) }
                CrashReport.resetStudioAttempts(this@DiagnosticsActivity)
                lastError = null
                forwardToStudio()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Try the Compose studio (may crash)"
            setOnClickListener {
                runCatching { startActivity(Intent(this@DiagnosticsActivity, MainActivity::class.java)) }
                    .onFailure { error ->
                        lastError = error
                        refresh()
                    }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        // Staged Compose bisection: tap them in order and note the first one that kills the app.
        listOf(
            1 to "Test 1: Compose basics",
            2 to "Test 2: + icons",
            3 to "Test 3: + scaffold, insets, text field",
            4 to "Test 4: + clipboard, prefs, model, crash report"
        ).forEach { (stage, label) ->
            root.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    runCatching { startActivity(SmokeActivity.intentFor(this@DiagnosticsActivity, stage)) }
                        .onFailure { error ->
                            lastError = error
                            refresh()
                        }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(Button(this).apply {
            text = "Share this report"
            setOnClickListener { share() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Reset and try the studio as a fresh install"
            setOnClickListener {
                CrashReport.resetStudioAttempts(this@DiagnosticsActivity)
                lastError = null
                forwardToStudio()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun showFallback(error: Throwable) {
        setContentView(
            TextView(this).apply {
                text = "WordCraft diagnostics failed to draw:\n\n$error"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }
        )
    }

    private fun refresh() {
        val report = CrashReport.latest(this)
        val attempts = CrashReport.studioAttempts(this)
        val explanation = when {
            lastError != null -> "Starting the studio failed inside this process:"
            report != null -> "Last failure:"
            attempts > 0 -> "No crash record was written, but the studio was opened " +
                "${attempts}x without ever reaching the screen. That points at the process being " +
                "killed before Android could record a reason."
            else -> "No failures recorded."
        }
        body.text = buildString {
            appendLine(CrashReport.deviceSummary(this@DiagnosticsActivity))
            appendLine()
            appendLine(explanation)
            appendLine()
            appendLine(
                lastError?.let { it.stackTraceToString() }
                    ?: report?.text
                    ?: "(no stack trace available)"
            )
        }
    }

    /**
     * The framework-widget studio. Handing over here rather than to the Compose screen is what
     * makes the app usable on a device where Compose cannot start; the Compose studio stays
     * reachable from the button above.
     */
    private fun studioIntent(): Intent =
        Intent(this, SimpleStudioActivity::class.java).also { it.putExtras(intent) }

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
