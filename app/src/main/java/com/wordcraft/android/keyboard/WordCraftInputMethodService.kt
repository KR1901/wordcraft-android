package com.wordcraft.android.keyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Local-only WordCraft IME. It never logs or transmits typed text.
 * This is a lightweight first-party keyboard, not yet a swipe-typing engine.
 */
class WordCraftInputMethodService : InputMethodService() {
    private var shifted = false
    private var symbols = false
    private var darkTheme = false
    private var root: LinearLayout? = null
    private var suggestions: LinearLayout? = null

    private val letters = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"),
        listOf("?123", ",", "☻", "English", ".", "↵")
    )
    private val numbers = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")"),
        listOf("=\\", "/", ":", ";", "!", "?", "⌫"),
        listOf("ABC", ",", "☻", "English", ".", "↵")
    )
    private val symbolRows = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"),
        listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}"),
        listOf("<", ">", "[", "]", "⌫"),
        listOf("ABC", ",", "☻", "English", ".", "↵")
    )
    private val corrections = mapOf(
        "teh" to "the", "adn" to "and", "dont" to "don't", "im" to "I'm",
        "wont" to "won't", "cant" to "can't", "recieve" to "receive",
        "thier" to "their", "becuase" to "because", "wierd" to "weird",
        "youre" to "you're", "its" to "it's", "ur" to "your"
    )

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("keyboard_preferences", MODE_PRIVATE)
        darkTheme = prefs.getBoolean("dark_theme", false)
    }

    override fun onCreateInputView(): View = buildKeyboard()

    private fun buildKeyboard(): View {
        val bg = if (darkTheme) Color.rgb(28, 29, 34) else Color.rgb(238, 240, 246)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(6), dp(5), dp(7))
            setBackgroundColor(bg)
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("WordCraft", "Grammar", "Rewrite", "◐", "⚙").forEachIndexed { index, label ->
            val item = TextView(this).apply {
                text = label
                setTextColor(if (index == 0) accent() else foreground())
                setTextSize(12f)
                typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener {
                    when (index) {
                        3 -> {
                            darkTheme = !darkTheme
                            getSharedPreferences("keyboard_preferences", MODE_PRIVATE).edit().putBoolean("dark_theme", darkTheme).apply()
                            inputView = buildKeyboard()
                        }
                        4 -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        1 -> runKeyboardAction("Fix grammar")
                        2 -> runKeyboardAction("Paraphrase")
                    }
                }
            }
            toolbar.addView(item, LinearLayout.LayoutParams(0, dp(36), if (index == 0) 1.3f else 1f))
        }
        root!!.addView(toolbar)

        suggestions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root!!.addView(suggestions, LinearLayout.LayoutParams(-1, dp(34)))
        refreshSuggestions()

        val rows = if (!symbols) letters else if (shifted) symbolRows else numbers
        rows.forEachIndexed { rowIndex, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { key ->
                val label = if (!symbols && shifted && key.length == 1 && key[0].isLetter()) key.uppercase(Locale.ROOT) else key
                val keyView = TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(foreground())
                    setTextSize(if (rowIndex == 3) 12f else 18f)
                    background = rounded(keyColor(key))
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                    setOnClickListener { handleKey(key) }
                }
                val weight = when (key) {
                    "English" -> 2.0f
                    "☻" -> 1.0f
                    "⇧", "⌫", "?123", "ABC", "↵" -> 1.35f
                    else -> 1f
                }
                line.addView(keyView, LinearLayout.LayoutParams(0, dp(if (rowIndex == 3) 42 else 45), weight).apply {
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                })
            }
            root!!.addView(line)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val space = keyButton("space", Color.WHITE) { currentInputConnection?.commitText(" ", 1); refreshSuggestions() }
        val done = keyButton("Done", accent(), true) { requestHideSelf(0) }
        bottom.addView(space, LinearLayout.LayoutParams(0, dp(40), 4f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        bottom.addView(done, LinearLayout.LayoutParams(0, dp(40), 1.2f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        root!!.addView(bottom)
        return root!!
    }

    private fun handleKey(key: String) {
        val connection: InputConnection = currentInputConnection ?: return
        when (key) {
            "⌫" -> {
                val selected = connection.getSelectedText(0)
                if (!selected.isNullOrEmpty()) connection.commitText("", 1)
                else connection.deleteSurroundingText(1, 0)
            }
            "↵" -> connection.commitText("\n", 1)
            "⇧" -> { shifted = !shifted; inputView = buildKeyboard() }
            "?123" -> { symbols = true; shifted = false; inputView = buildKeyboard() }
            "ABC" -> { symbols = false; shifted = false; inputView = buildKeyboard() }
            "English" -> switchToNextInputMethod(false)
            "☻" -> connection.commitText("😊", 1)
            else -> {
                val output = if (shifted && !symbols) key.uppercase(Locale.ROOT) else key
                connection.commitText(output, 1)
                if (shifted && !symbols) { shifted = false; inputView = buildKeyboard() }
                refreshSuggestions()
            }
        }
    }

    private fun refreshSuggestions() {
        val strip = suggestions ?: return
        strip.removeAllViews()
        val before = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        val correction = corrections[word.lowercase(Locale.ROOT)]
        val options = listOfNotNull(correction, if (word.isNotEmpty()) word else null, if (word.isNotEmpty()) word.replaceFirstChar { it.titlecase(Locale.ROOT) } else null).distinct()
        val shown = if (options.isEmpty()) listOf("WordCraft", "Suggestions appear here") else options.take(3)
        shown.forEach { option ->
            val item = TextView(this).apply {
                text = option
                gravity = Gravity.CENTER
                setTextColor(foreground())
                setTextSize(12f)
                setPadding(dp(5), dp(4), dp(5), dp(4))
                setOnClickListener {
                    if (correction != null && option == correction && word.isNotEmpty()) {
                        currentInputConnection?.deleteSurroundingText(word.length, 0)
                        currentInputConnection?.commitText(option, 1)
                    }
                    refreshSuggestions()
                }
            }
            strip.addView(item, LinearLayout.LayoutParams(0, dp(32), 1f))
        }
    }

    private fun runKeyboardAction(actionLabel: String) {
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        val text = if (selected.isNotBlank()) selected else connection.getTextBeforeCursor(300, 0)?.toString().orEmpty()
        if (text.isBlank()) {
            // The app contains the full on-device AI writing studio; the IME action stays local.
            connection.commitText("", 1)
            return
        }
        // Do not pretend an AI rewrite happened: selected text can be copied to the studio explicitly.
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WordCraft draft", text))
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch?.putExtra("wordcraft_keyboard_action", actionLabel)
        launch?.putExtra("wordcraft_keyboard_text", text)
        if (launch != null) startActivity(launch)
    }

    private fun keyButton(label: String, color: Int, inverse: Boolean = false, click: () -> Unit) =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (inverse) Color.WHITE else foreground())
            background = rounded(color)
            setOnClickListener { click() }
        }

    private fun keyColor(key: String): Int = when (key) {
        "⇧", "⌫", "?123", "ABC", "↵", "English" -> if (darkTheme) Color.rgb(63, 65, 76) else Color.rgb(211, 215, 228)
        else -> if (darkTheme) Color.rgb(47, 49, 57) else Color.WHITE
    }
    private fun foreground() = if (darkTheme) Color.rgb(245, 245, 250) else Color.rgb(32, 36, 48)
    private fun accent() = if (darkTheme) Color.rgb(166, 159, 255) else Color.rgb(85, 78, 170)
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
