package com.wordcraft.android.keyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.wordcraft.android.MainActivity
import java.util.Locale

/**
 * Local-only WordCraft IME. It never logs or transmits typed text.
 * This is a lightweight first-party keyboard, not yet a swipe-typing engine.
 */
class WordCraftInputMethodService : InputMethodService() {
    private var shifted = false
    private var page = PAGE_LETTERS
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
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "=\\<"),
        listOf("/", ":", ";", "!", "?", "⌫"),
        listOf("ABC", ",", "☻", "English", ".", "↵")
    )
    private val symbolRows = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"),
        listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "?123"),
        listOf("<", ">", "[", "]", "⌫"),
        listOf("ABC", ",", "☻", "English", ".", "↵")
    )
    private val corrections = mapOf(
        "teh" to "the", "adn" to "and", "dont" to "don't", "im" to "I'm",
        "wont" to "won't", "cant" to "can't", "recieve" to "receive",
        "thier" to "their", "becuase" to "because", "wierd" to "weird",
        "youre" to "you're", "ur" to "your"
    )

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("keyboard_preferences", MODE_PRIVATE)
        darkTheme = prefs.getBoolean("dark_theme", false)
    }

    override fun onCreateInputView(): View = buildKeyboard()

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A new field starts unshifted and must not inherit the previous field's suggestions.
        shifted = false
        refreshSuggestions()
    }

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
                isClickable = true
                contentDescription = label
                setOnClickListener {
                    when (index) {
                        3 -> {
                            darkTheme = !darkTheme
                            getSharedPreferences("keyboard_preferences", MODE_PRIVATE).edit().putBoolean("dark_theme", darkTheme).apply()
                            setInputView(buildKeyboard())
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

        val rows = when (page) {
            PAGE_LETTERS -> letters
            PAGE_NUMBERS -> numbers
            else -> symbolRows
        }
        rows.forEachIndexed { rowIndex, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { key ->
                val label = if (page == PAGE_LETTERS && shifted && key.length == 1 && key[0].isLetter()) {
                    key.uppercase(Locale.ROOT)
                } else {
                    key
                }
                val keyView = TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(foreground())
                    setTextSize(if (rowIndex == 3) 12f else 18f)
                    background = rounded(keyColor(key))
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                    isClickable = true
                    contentDescription = keyLabel(key)
                    setOnClickListener { handleKey(key) }
                }
                val weight = when (key) {
                    "English" -> 2.0f
                    "☻" -> 1.0f
                    "⇧", "⌫", "?123", "ABC", "↵", "=\\<" -> 1.35f
                    else -> 1f
                }
                line.addView(keyView, LinearLayout.LayoutParams(0, dp(if (rowIndex == 3) 42 else 45), weight).apply {
                    setMargins(dp(2), dp(3), dp(2), dp(3))
                })
            }
            root!!.addView(line)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val space = keyButton("space", keyColor("space")) { currentInputConnection?.commitText(" ", 1); refreshSuggestions() }
        val done = keyButton("Done", accent(), inverse = true) { requestHideSelf(0) }
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
                else connection.deleteSurroundingTextInCodePoints(1, 0)
            }
            "↵" -> connection.commitText("\n", 1)
            "⇧" -> { shifted = !shifted; setInputView(buildKeyboard()) }
            "?123" -> { page = PAGE_NUMBERS; shifted = false; setInputView(buildKeyboard()) }
            "=\\<" -> { page = PAGE_SYMBOLS; shifted = false; setInputView(buildKeyboard()) }
            "ABC" -> { page = PAGE_LETTERS; shifted = false; setInputView(buildKeyboard()) }
            "English" -> switchToNextInputMethod(false)
            "☻" -> connection.commitText("😊", 1)
            else -> {
                val output = if (page == PAGE_LETTERS && shifted) key.uppercase(Locale.ROOT) else key
                connection.commitText(output, 1)
                if (page == PAGE_LETTERS && shifted) { shifted = false; setInputView(buildKeyboard()) }
                refreshSuggestions()
            }
        }
    }

    /** The word being typed right now, read fresh from the editor instead of a stale closure. */
    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        return before.takeLastWhile { it.isLetter() || it == '\'' }
    }

    private fun refreshSuggestions() {
        val strip = suggestions ?: return
        strip.removeAllViews()
        val word = currentWord()
        if (word.isEmpty()) {
            addSuggestion(strip, "WordCraft", null)
            addSuggestion(strip, "Suggestions appear here", null)
            return
        }
        val correction = corrections[word.lowercase(Locale.ROOT)]
        val capitalized = word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val options = listOfNotNull(correction, capitalized.takeIf { it != word })
            .distinct()
            .take(3)
        options.forEach { option -> addSuggestion(strip, option, option) }
    }

    /**
     * @param replacement when non-null the chip rewrites the current word; when null it is
     *   informational only, so tapping it does nothing surprising.
     */
    private fun addSuggestion(strip: LinearLayout, label: String, replacement: String?) {
        val item = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(foreground())
            setTextSize(12f)
            setPadding(dp(5), dp(4), dp(5), dp(4))
            isClickable = replacement != null
            contentDescription = if (replacement != null) "Replace with $label" else label
            setOnClickListener {
                if (replacement != null) {
                    replaceCurrentWord(replacement)
                    refreshSuggestions()
                }
            }
        }
        strip.addView(item, LinearLayout.LayoutParams(0, dp(32), 1f))
    }

    private fun replaceCurrentWord(replacement: String) {
        val connection = currentInputConnection ?: return
        val word = currentWord()
        if (word.isNotEmpty()) connection.deleteSurroundingTextInCodePoints(word.length, 0)
        connection.commitText(replacement, 1)
    }

    private fun runKeyboardAction(actionLabel: String) {
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        val text = if (selected.isNotBlank()) selected else connection.getTextBeforeCursor(300, 0)?.toString().orEmpty()
        if (text.isBlank()) {
            // Nothing to hand over: say so instead of silently doing nothing.
            Toast.makeText(this, "Type or select some text first", Toast.LENGTH_SHORT).show()
            return
        }
        // Do not pretend an AI rewrite happened: the text is handed to the studio explicitly.
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WordCraft draft", text))
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch?.putExtra(MainActivity.EXTRA_KEYBOARD_ACTION, actionLabel)
        launch?.putExtra(MainActivity.EXTRA_KEYBOARD_TEXT, text)
        if (launch != null) startActivity(launch)
    }

    private fun keyButton(label: String, color: Int, inverse: Boolean = false, click: () -> Unit) =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (inverse) Color.WHITE else foreground())
            setTextSize(14f)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            background = rounded(color)
            isClickable = true
            contentDescription = label
            setOnClickListener { click() }
        }

    private fun keyColor(key: String): Int = when (key) {
        "⇧", "⌫", "?123", "ABC", "↵", "English", "=\\<" -> if (darkTheme) Color.rgb(63, 65, 76) else Color.rgb(211, 215, 228)
        else -> if (darkTheme) Color.rgb(47, 49, 57) else Color.WHITE
    }
    private fun keyLabel(key: String): String = when (key) {
        "⌫" -> "Delete"
        "⇧" -> "Shift"
        "↵" -> "Enter"
        "?123" -> "Numbers"
        "=\\<" -> "Symbols"
        "ABC" -> "Letters"
        "☻" -> "Emoji"
        "English" -> "Next keyboard"
        else -> key
    }
    private fun foreground() = if (darkTheme) Color.rgb(245, 245, 250) else Color.rgb(32, 36, 48)
    private fun accent() = if (darkTheme) Color.rgb(166, 159, 255) else Color.rgb(85, 78, 170)
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_LETTERS = 0
        const val PAGE_NUMBERS = 1
        const val PAGE_SYMBOLS = 2
    }
}
