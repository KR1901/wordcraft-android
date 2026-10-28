package com.wordcraft.android.keyboard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keyboard preferences, exposed as Compose state so the settings screen edits them live.
 * The IME reads the same object, so a change applies the next time the keyboard view is built.
 */
class KeyboardPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("keyboard_preferences", Context.MODE_PRIVATE)

    /** null = follow the system. */
    var darkTheme: Boolean? by mutableStateOf(
        if (prefs.contains(KEY_DARK)) prefs.getBoolean(KEY_DARK, false) else null
    )

    var soundEnabled by mutableStateOf(prefs.getBoolean(KEY_SOUND, true))
    var hapticsEnabled by mutableStateOf(prefs.getBoolean(KEY_HAPTICS, true))
    var numberRow by mutableStateOf(prefs.getBoolean(KEY_NUMBER_ROW, false))
    var smartPunctuation by mutableStateOf(prefs.getBoolean(KEY_SMART_PUNCT, true))
    var autoCapitalize by mutableStateOf(prefs.getBoolean(KEY_AUTO_CAPS, true))
    var autoCorrect by mutableStateOf(prefs.getBoolean(KEY_AUTOCORRECT, true))
    var suggestionsEnabled by mutableStateOf(prefs.getBoolean(KEY_SUGGESTIONS, true))

    /** 0 = compact, 1 = normal, 2 = tall. */
    var keyHeight by mutableStateOf(prefs.getInt(KEY_HEIGHT, 1))

    /** Re-reads the stored values, so a live keyboard picks up changes made in settings. */
    fun reload() {
        darkTheme = if (prefs.contains(KEY_DARK)) prefs.getBoolean(KEY_DARK, false) else null
        soundEnabled = prefs.getBoolean(KEY_SOUND, true)
        hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true)
        numberRow = prefs.getBoolean(KEY_NUMBER_ROW, false)
        smartPunctuation = prefs.getBoolean(KEY_SMART_PUNCT, true)
        autoCapitalize = prefs.getBoolean(KEY_AUTO_CAPS, true)
        autoCorrect = prefs.getBoolean(KEY_AUTOCORRECT, true)
        suggestionsEnabled = prefs.getBoolean(KEY_SUGGESTIONS, true)
        keyHeight = prefs.getInt(KEY_HEIGHT, 1)
    }

    fun setKeyHeight(value: Int) {
        keyHeight = value.coerceIn(0, 2)
        prefs.edit().putInt(KEY_HEIGHT, keyHeight).apply()
    }

    fun setTheme(dark: Boolean?) {
        darkTheme = dark
        prefs.edit().apply {
            if (dark == null) remove(KEY_DARK) else putBoolean(KEY_DARK, dark)
        }.apply()
    }

    fun save() {
        prefs.edit()
            .putBoolean(KEY_SOUND, soundEnabled)
            .putBoolean(KEY_HAPTICS, hapticsEnabled)
            .putBoolean(KEY_NUMBER_ROW, numberRow)
            .putBoolean(KEY_SMART_PUNCT, smartPunctuation)
            .putBoolean(KEY_AUTO_CAPS, autoCapitalize)
            .putBoolean(KEY_AUTOCORRECT, autoCorrect)
            .putBoolean(KEY_SUGGESTIONS, suggestionsEnabled)
            .apply()
    }

    private companion object {
        const val KEY_DARK = "dark_theme"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_SMART_PUNCT = "smart_punctuation"
        const val KEY_AUTO_CAPS = "auto_capitalize"
        const val KEY_AUTOCORRECT = "auto_correct"
        const val KEY_SUGGESTIONS = "suggestions_enabled"
        const val KEY_HEIGHT = "key_height"
    }
}
