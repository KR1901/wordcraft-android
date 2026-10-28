package com.wordcraft.android.keyboard

import android.view.View
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * Compose-hosted IME. The view is deliberately not focusable: the keyboard must never take focus
 * from the editor, which is why keys use raw pointer input instead of clickable modifiers.
 */
class WordCraftInputMethodService : InputMethodService() {
    private lateinit var controller: KeyboardController

    override fun onCreate() {
        super.onCreate()
        controller = KeyboardController(this, KeyboardPrefs(this), WordEngine(this))
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = false
            isFocusableInTouchMode = false
        }
        controller.attachHapticTarget(view)
        view.setContent { WordCraftKeyboard(controller) }
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Pick up any settings change made while the keyboard was in the background.
        controller.prefs.reload()
        controller.onStartInput()
    }

    override fun onFinishInput() {
        controller.onFinishInput()
        super.onFinishInput()
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }
}
