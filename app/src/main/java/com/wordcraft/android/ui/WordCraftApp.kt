package com.wordcraft.android.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wordcraft.android.CrashReport
import com.wordcraft.android.SettingsActivity
import com.wordcraft.android.ai.OnDeviceQwenWritingAssistant
import com.wordcraft.android.ai.QwenModelStore
import com.wordcraft.android.ai.WritingAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DRAFT_PREFS = "wordcraft_documents"
private const val DRAFT_KEY = "draft_default"
private const val DRAFT_SAVE_DELAY_MS = 400L

/**
 * @param resumeTick changes every time the activity resumes, so system settings and the selected
 *   model are re-read instead of being frozen at first composition.
 * @param initialText draft handed over from the keyboard's toolbar.
 * @param initialAction action requested by the keyboard, if any.
 */
@Composable
fun WordCraftApp(
    resumeTick: Int = 0,
    initialText: String? = null,
    initialAction: WritingAction? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val draftStore = remember(context) { context.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE) }
    var draft by remember {
        mutableStateOf(
            initialText?.takeIf { it.isNotBlank() }
                ?: draftStore.getString(DRAFT_KEY, "Your words, only clearer.") ?: ""
        )
    }
    var chosenAction by remember { mutableStateOf(initialAction ?: WritingAction.FIX_GRAMMAR) }
    var result by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("Ready when you are.") }
    var isWorking by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(QwenModelStore.isModelDownloaded(context)) }
    var modelLabel by remember { mutableStateOf(QwenModelStore.active(context).label) }
    var keyboardEnabled by remember { mutableStateOf(false) }
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    val scope = rememberCoroutineScope()
    var crashReport by remember(context) { mutableStateOf(CrashReport.latest(context)) }

    LaunchedEffect(resumeTick) {
        keyboardEnabled = isOurKeyboardEnabled(context)
        modelReady = QwenModelStore.isModelDownloaded(context)
        modelLabel = QwenModelStore.active(context).label
    }
    LaunchedEffect(initialText, initialAction) {
        if (!initialText.isNullOrBlank()) {
            draft = initialText
            initialAction?.let { chosenAction = it }
        }
    }
    // Debounced persistence: avoids a disk write per keystroke and stores the draft as typed.
    LaunchedEffect(draft) {
        delay(DRAFT_SAVE_DELAY_MS)
        draftStore.edit().putString(DRAFT_KEY, draft).apply()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (keyboardEnabled) inputMethodManager.showInputMethodPicker()
                    else context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null) },
                text = { Text(if (keyboardEnabled) "Switch to WordCraft" else "Enable WordCraft keyboard") }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()).padding(insets)
                .padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("WordCraft", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (modelReady) "$modelLabel · on this device" else "No model downloaded yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }

            crashReport?.let { report ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Previous session crashed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            report.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 18
                        )
                        Row {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(report.text))
                                notice = "Crash details copied."
                            }) { Text("Copy details") }
                            TextButton(onClick = {
                                CrashReport.dismiss(context, report)
                                crashReport = null
                            }) { Text("Dismiss") }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "A little more you.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Write or paste something, pick a tone, and read the rewrite before you send it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------------------------------------------------------------- input
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${draft.length} chars",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        shape = RoundedCornerShape(20.dp),
                        placeholder = { Text("Paste or write something…") },
                        supportingText = { Text("Stays on this device.") }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WritingAction.entries.forEach { action ->
                            FilterChip(
                                selected = chosenAction == action,
                                onClick = { chosenAction = action },
                                label = { Text(action.label) }
                            )
                        }
                    }
                    Button(
                        enabled = !isWorking,
                        onClick = {
                            when {
                                draft.isBlank() -> notice = "Add some text first."
                                !modelReady -> {
                                    notice = "Download a model in settings first."
                                    context.startActivity(Intent(context, SettingsActivity::class.java))
                                }
                                else -> scope.launch {
                                    isWorking = true
                                    notice = "Running ${chosenAction.label.lowercase()} on this device…"
                                    try {
                                        val rewritten = OnDeviceQwenWritingAssistant(context)
                                            .rewrite(draft, chosenAction)
                                        result = rewritten.text
                                        notice = "Done — nothing left this phone."
                                    } catch (error: Throwable) {
                                        notice = error.message ?: "Local AI failed. Try again."
                                    } finally {
                                        isWorking = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isWorking) "Working…" else chosenAction.label)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(draft))
                            notice = "Input copied."
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy")
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- output
            result?.let { output ->
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Output",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${output.length} chars",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        OutlinedTextField(
                            value = output,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            shape = RoundedCornerShape(20.dp),
                            label = { Text("Rewritten text") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                clipboard.setText(AnnotatedString(output))
                                notice = "Output copied."
                            }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy")
                            }
                            FilledTonalButton(onClick = {
                                draft = output
                                result = null
                                notice = "Moved into your text."
                            }) { Text("Use as input") }
                            TextButton(onClick = { result = null }) { Text("Dismiss") }
                        }
                    }
                }
            }

            Text(
                "Made for your voice · Private by design",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

/**
 * Whether the WordCraft IME is currently enabled.
 *
 * Do **not** read Settings.Secure.ENABLED_INPUT_METHODS here: Android denies that key to apps
 * targeting SDK 34+ and throws SecurityException("Settings key: <enabled_input_methods> is only
 * readable to apps with targetSdkVersion lower than or equal to: 33"). That exception is thrown on
 * the first frame, so it looks like "the app crashes on open" — it was the startup crash on Android
 * 14+ until this was replaced. InputMethodManager exposes the same information publicly.
 */
private fun isOurKeyboardEnabled(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    manager.enabledInputMethodList.any { it.packageName == context.packageName }
}.getOrDefault(false)
