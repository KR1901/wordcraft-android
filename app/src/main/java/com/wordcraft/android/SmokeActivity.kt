package com.wordcraft.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.wordcraft.android.ai.QwenModelStore

/**
 * Staged Compose smoke test. Each stage adds exactly one ingredient the writing studio uses, so
 * the first stage that crashes identifies the culprit on the device itself:
 *
 *   1. theme + text                     (Compose can start at all)
 *   2. + material-icons-extended icons  (vector rendering / androidx.graphics.path native library)
 *   3. + Scaffold, window insets, FAB, text field
 *   4. + clipboard, preferences, model status, crash-report lookup
 */
class SmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stage = intent.getIntExtra(EXTRA_STAGE, 1)
        setContent {
            MaterialTheme {
                if (stage >= 3) {
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        floatingActionButton = {
                            ExtendedFloatingActionButton(
                                onClick = {},
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                text = { Text("Stage 3") }
                            )
                        }
                    ) { insets ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(insets)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StageText(stage)
                            IconsIfNeeded(stage)
                            var value by remember { mutableStateOf("Typed text") }
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = { Text("Stage 3 text field") }
                            )
                            ExtrasIfNeeded(stage)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StageText(stage)
                        IconsIfNeeded(stage)
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun StageText(stage: Int) {
        Text("Stage $stage rendered.", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tell the developer that stage $stage worked and the next one did not.",
            style = MaterialTheme.typography.bodyMedium
        )
    }

    @androidx.compose.runtime.Composable
    private fun IconsIfNeeded(stage: Int) {
        if (stage >= 2) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
            Icon(Icons.Rounded.Spellcheck, contentDescription = null)
            Text("Stage 2: icons drawn.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @androidx.compose.runtime.Composable
    private fun ExtrasIfNeeded(stage: Int) {
        if (stage >= 4) {
            val context = LocalContext.current
            val clipboard = LocalClipboardManager.current
            val prefs = remember { context.getSharedPreferences("wordcraft_diagnostics", 0) }
            val modelReady = QwenModelStore.isModelDownloaded(context)
            val report = CrashReport.latest(context)
            clipboard.setText(AnnotatedString("probe"))
            prefs.edit().putString("probe", "1").apply()
            Text(
                "Stage 4: clipboard, preferences, model=$modelReady, crashReport=${report != null}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    companion object {
        const val EXTRA_STAGE = "stage"
        fun intentFor(activity: android.content.Context, stage: Int): Intent =
            Intent(activity, SmokeActivity::class.java).putExtra(EXTRA_STAGE, stage)
    }
}
