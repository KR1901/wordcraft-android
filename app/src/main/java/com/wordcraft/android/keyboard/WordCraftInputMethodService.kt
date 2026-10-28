package com.wordcraft.android.keyboard

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * Compose-hosted IME. The view is deliberately not focusable: the keyboard must never take focus
 * from the editor, which is why keys use raw pointer input instead of clickable modifiers.
 *
 * The window's decor view carries the [KeyboardLifecycleOwner] before `setContent`, because
 * Compose looks the owner up from the root of the view tree and walks upwards — tagging the
 * ComposeView alone is invisible to that lookup and produces
 * "IllegalStateException: ViewTreeLifecycleOwner not found".
 */
class WordCraftInputMethodService : InputMethodService() {
    private lateinit var controller: KeyboardController
    private val lifecycleOwner = KeyboardLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        controller = KeyboardController(this, KeyboardPrefs(this), WordEngine(this))
        lifecycleOwner.start()
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = false
            isFocusableInTouchMode = false
        }
        publishOwner(view)

        // Belt and braces: if the owner is still not visible from the window root, Compose would
        // throw while the view is being attached, which cannot be caught. Show a plain fallback
        // keyboard instead of taking the app process down.
        val windowRoot = runCatching { window?.window?.decorView }.getOrNull()
        if (windowRoot != null && !lifecycleOwner.isVisibleFrom(windowRoot)) {
            return fallbackView()
        }

        controller.attachHapticTarget(view)
        view.setContent { WordCraftKeyboard(controller) }
        return view
    }

    private fun fallbackView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 32, 32, 32)
        setBackgroundColor(Color.WHITE)
        addView(TextView(this@WordCraftInputMethodService).apply {
            text = "WordCraft keyboard could not start on this device. Tap here to open keyboard settings."
            setTextColor(Color.BLACK)
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        })
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleOwner.resume()
        // The decor view can be recreated while the IME lives, so re-publish on every show.
        currentInputView?.let { publishOwner(it) }
        controller.prefs.reload()
        controller.onStartInput()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        runCatching { window?.window?.decorView }.getOrNull()?.let { lifecycleOwner.attachTo(it) }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        lifecycleOwner.pause()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        controller.onFinishInput()
        super.onFinishInput()
    }

    override fun onDestroy() {
        controller.destroy()
        lifecycleOwner.destroy()
        super.onDestroy()
    }

    /** The owner must be on the ComposeView *and* on the window root above it. */
    private fun publishOwner(composeView: View) {
        lifecycleOwner.attachTo(composeView)
        runCatching { window?.window?.decorView }.getOrNull()?.let { lifecycleOwner.attachTo(it) }
        (composeView.parent as? View)?.let { lifecycleOwner.attachTo(it) }
    }
}
