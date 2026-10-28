package com.wordcraft.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.wordcraft.android.ai.OnDeviceQwenWritingAssistant
import com.wordcraft.android.ai.QwenModelStore
import com.wordcraft.android.ai.WritingAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The writing studio built from framework widgets only: no Compose, no androidx, no theme
 * resources. It does the same work as the Compose studio — edit text, pick an action, run the
 * on-device model, copy, download the model — and it keeps working on devices where the Compose
 * screen cannot start.
 */
class SimpleStudioActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var draft: EditText
    private lateinit var status: TextView
    private lateinit var actionPicker: Spinner
    private lateinit var improveButton: Button
    private lateinit var downloadButton: Button
    private val actions = WritingAction.entries

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("wordcraft_documents", MODE_PRIVATE)
        val model = QwenModelStore.active(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(title("WordCraft"))
        status = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(14))
            text = if (QwenModelStore.isModelDownloaded(this@SimpleStudioActivity)) {
                "${model.label} is ready on this device."
            } else {
                "Download ${model.label} (${model.sizeLabel}) once, then everything runs offline."
            }
        }
        root.addView(status)

        draft = EditText(this).apply {
            setText(prefs.getString("draft_default", "") ?: "")
            hint = "Paste or write something…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(draft, LinearLayout.LayoutParams(-1, dp(220)))

        root.addView(sectionLabel("Improve your writing"))
        actionPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SimpleStudioActivity,
                android.R.layout.simple_spinner_dropdown_item,
                actions.map { it.label }
            )
        }
        root.addView(actionPicker, LinearLayout.LayoutParams(-1, -2))

        improveButton = Button(this).apply {
            text = "Improve writing"
            setOnClickListener { improve() }
        }
        root.addView(improveButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        root.addView(Button(this).apply {
            text = "Copy text"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("WordCraft draft", draft.text.toString()))
                status.text = "Copied to clipboard."
            }
        }, LinearLayout.LayoutParams(-1, -2))

        downloadButton = Button(this).apply {
            text = if (QwenModelStore.isModelDownloaded(this@SimpleStudioActivity)) {
                "Model downloaded"
            } else {
                "Download ${model.label}"
            }
            setOnClickListener { downloadModel() }
        }
        root.addView(downloadButton, LinearLayout.LayoutParams(-1, -2))

        root.addView(Button(this).apply {
            text = "Open keyboard settings"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(Button(this).apply {
            text = "Diagnostics"
            setOnClickListener { startActivity(Intent(this@SimpleStudioActivity, DiagnosticsActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply { addView(root) })

        // Text handed over by the keyboard's Grammar/Rewrite buttons.
        val handoff = intent?.getStringExtra(MainActivity.EXTRA_KEYBOARD_TEXT)
        val requested = intent?.getStringExtra(MainActivity.EXTRA_KEYBOARD_ACTION)
        if (!handoff.isNullOrBlank()) {
            draft.setText(handoff)
            requested?.let { label ->
                val index = actions.indexOfFirst { it.label == label }
                if (index >= 0) actionPicker.setSelection(index)
            }
        }
    }

    private fun improve() {
        val text = draft.text.toString()
        if (text.isBlank()) {
            status.text = "Add some text first."
            return
        }
        if (!QwenModelStore.isModelDownloaded(this)) {
            status.text = "Download a model in WordCraft settings first."
            return
        }
        val action = actions.getOrNull(actionPicker.selectedItemPosition) ?: WritingAction.FIX_GRAMMAR
        improveButton.isEnabled = false
        improveButton.text = "Improving…"
        status.text = "Running Qwen locally on this device…"
        scope.launch {
            try {
                val result = OnDeviceQwenWritingAssistant(this@SimpleStudioActivity).rewrite(text, action)
                draft.setText(result.text)
                status.text = "Done — processed on your device."
            } catch (error: Throwable) {
                status.text = error.message ?: "Local AI failed. Try again."
            } finally {
                improveButton.isEnabled = true
                improveButton.text = "Improve writing"
            }
        }
    }

    private fun downloadModel() {
        if (QwenModelStore.isModelDownloaded(this)) {
            status.text = "${QwenModelStore.active(this).label} is already on this device."
            return
        }
        downloadButton.isEnabled = false
        downloadButton.text = "Downloading…"
        scope.launch {
            try {
                QwenModelStore.downloadModel(this@SimpleStudioActivity) { downloaded, total ->
                    status.text = if (total > 0L) {
                        "Downloading model… ${downloaded / 1_000_000} / ${total / 1_000_000} MB"
                    } else {
                        "Downloading model… ${downloaded / 1_000_000} MB"
                    }
                }
                status.text = "${QwenModelStore.active(this@SimpleStudioActivity).label} is ready. Everything now runs offline."
                downloadButton.text = "Model downloaded"
            } catch (error: Throwable) {
                status.text = error.message ?: "Download failed."
                downloadButton.isEnabled = true
                downloadButton.text = "Download ${QwenModelStore.active(this@SimpleStudioActivity).label}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reaching here means the studio came up; clear the diagnostics launch counter.
        CrashReport.noteStudioAlive(this)
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("wordcraft_documents", MODE_PRIVATE)
            .edit().putString("draft_default", draft.text.toString()).apply()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
