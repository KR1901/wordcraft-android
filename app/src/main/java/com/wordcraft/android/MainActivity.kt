package com.wordcraft.android
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wordcraft.android.ai.WritingAction
import com.wordcraft.android.ui.WordCraftApp
import com.wordcraft.android.ui.theme.WordCraftTheme

class MainActivity : ComponentActivity() {
    // Recomposes on every resume so system settings (enabled IMEs) are re-read.
    private var resumeTick by mutableIntStateOf(0)
    // Draft/action handed over by the keyboard's Grammar and Rewrite buttons.
    private var handoffText by mutableStateOf<String?>(null)
    private var handoffAction by mutableStateOf<WritingAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readKeyboardHandoff(intent)
        setContent {
            WordCraftTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WordCraftApp(
                        resumeTick = resumeTick,
                        initialText = handoffText,
                        initialAction = handoffAction
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reaching here means the studio actually came up; clear the diagnostics launch counter.
        CrashReport.noteStudioAlive(this)
        resumeTick++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readKeyboardHandoff(intent)
    }

    /** Consumes the keyboard handoff extras once, so a rotation does not re-apply stale text. */
    private fun readKeyboardHandoff(intent: Intent?) {
        if (intent == null) return
        val text = intent.getStringExtra(EXTRA_KEYBOARD_TEXT)
        val label = intent.getStringExtra(EXTRA_KEYBOARD_ACTION)
        intent.removeExtra(EXTRA_KEYBOARD_TEXT)
        intent.removeExtra(EXTRA_KEYBOARD_ACTION)
        if (text.isNullOrBlank()) return
        handoffText = text
        handoffAction = label?.let { requested ->
            WritingAction.entries.firstOrNull { it.label == requested }
        }
    }

    companion object {
        const val EXTRA_KEYBOARD_TEXT = "wordcraft_keyboard_text"
        const val EXTRA_KEYBOARD_ACTION = "wordcraft_keyboard_action"
    }
}
