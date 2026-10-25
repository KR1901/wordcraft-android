package com.wordcraft.android.keyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal local-only IME foundation. It does not store or transmit keystrokes. */
class WordCraftInputMethodService : InputMethodService() {
    private val keyRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"),
        listOf("?123", ",", "☻", "English", ".", "↵")
    )

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setBackgroundColor(Color.rgb(238, 240, 246))
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("WordCraft", "Grammar", "Rewrite", "⚙").forEachIndexed { index, label ->
            val item = TextView(this).apply {
                text = label
                setTextColor(if (index == 0) Color.rgb(70, 65, 145) else Color.rgb(42, 46, 60))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (index == 0) 14f else 12f)
                typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    if (index == 3) {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            toolbar.addView(item, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(toolbar)

        keyRows.forEachIndexed { rowIndex, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { key ->
                val keyView = TextView(this).apply {
                    text = key; gravity = Gravity.CENTER
                    setTextColor(Color.rgb(32, 36, 48))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (rowIndex == 3) 12f else 18f)
                    background = rounded(if (key in listOf("⇧", "⌫", "?123", "↵", "English")) Color.rgb(211, 215, 228) else Color.WHITE)
                    setPadding(dp(2), dp(10), dp(2), dp(10))
                    setOnClickListener { handleKey(key) }
                }
                val weight = when (key) {
                    "English" -> 2.2f
                    "☻" -> 1.2f
                    "⇧", "⌫", "?123", "↵" -> 1.3f
                    else -> 1f
                }
                line.addView(keyView, LinearLayout.LayoutParams(0, dp(if (rowIndex == 3) 44 else 46), weight).apply {
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                })
            }
            root.addView(line)
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val space = TextView(this).apply {
            text = "space"; gravity = Gravity.CENTER; setTextColor(Color.rgb(32, 36, 48)); background = rounded(Color.WHITE)
            setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        }
        val done = TextView(this).apply {
            text = "Done"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded(Color.rgb(85, 78, 170))
            setOnClickListener { requestHideSelf(0) }
        }
        bottom.addView(space, LinearLayout.LayoutParams(0, dp(42), 4f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        bottom.addView(done, LinearLayout.LayoutParams(0, dp(42), 1.3f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        root.addView(bottom)
        return root
    }

    private fun handleKey(key: String) {
        val connection: InputConnection = currentInputConnection ?: return
        when (key) {
            "⌫" -> connection.deleteSurroundingText(1, 0)
            "↵" -> connection.commitText("\n", 1)
            "⇧", "?123", "English", "☻" -> Unit
            else -> connection.commitText(key, 1)
        }
    }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
