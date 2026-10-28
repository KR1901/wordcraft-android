package com.wordcraft.android.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.wordcraft.android.ai.OnDeviceQwenWritingAssistant
import com.wordcraft.android.ai.QwenModelStore
import com.wordcraft.android.ai.WritingAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val DRAFT_PREFS = "wordcraft_documents"
private const val DRAFT_KEY = "draft_default"
private const val DRAFT_SAVE_DELAY_MS = 400L

/**
 * @param resumeTick changes every time the activity resumes, so system settings such as
 *   "is this IME enabled" are re-read instead of being frozen at first composition.
 * @param initialText draft handed over from the keyboard's Grammar/Rewrite buttons.
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
    var showSetup by remember { mutableStateOf(false) }
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    var keyboardEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(resumeTick) {
        keyboardEnabled = isOurKeyboardEnabled(context)
    }
    // Text handed over by the keyboard while this activity is already running.
    LaunchedEffect(initialText, initialAction) {
        if (!initialText.isNullOrBlank()) {
            draft = initialText
            initialAction?.let { chosenAction = it }
        }
    }
    // Debounced persistence: avoids a disk write per keystroke and also stores AI rewrites.
    LaunchedEffect(draft) {
        delay(DRAFT_SAVE_DELAY_MS)
        draftStore.edit().putString(DRAFT_KEY, draft).apply()
    }
    var notice by remember { mutableStateOf("Ready when you are.") }
    val scope = rememberCoroutineScope()
    // Status checks use QwenModelStore only — never loads LiteRT native code at startup.
    var modelReady by remember { mutableStateOf(QwenModelStore.isModelDownloaded(context)) }
    var isDownloading by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    // A StateFlow, not plain state: download progress arrives from an IO thread.
    val progressFlow = remember { MutableStateFlow("Not downloaded") }
    val modelProgress by progressFlow.collectAsState()
    // Why the previous run ended, if it ended badly. Read once, on the main thread, from a
    // small file plus the system's process-exit history.
    var crashReport by remember(context) { mutableStateOf(CrashReport.latest(context)) }

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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("WordCraft", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Write with confidence", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showSetup = !showSetup }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            }

            crashReport?.let { report ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A little more you.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("A thoughtful writing space for clearer messages, better sentences, and your own voice.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Spellcheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Writing studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${draft.length} chars", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().height(190.dp), shape = RoundedCornerShape(20.dp),
                        placeholder = { Text("Paste or write something…") },
                        supportingText = { Text("Your draft stays on this device. Nothing is uploaded.") }
                    )
                    Text("Improve your writing", style = MaterialTheme.typography.titleSmall)
                    val rows = listOf(
                        listOf(WritingAction.FIX_GRAMMAR, WritingAction.PARAPHRASE),
                        listOf(WritingAction.PROFESSIONAL, WritingAction.FRIENDLY),
                        listOf(WritingAction.CONCISE, WritingAction.SIMPLIFY),
                        listOf(WritingAction.EXPAND)
                    )
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { action ->
                                if (chosenAction == action) {
                                    FilledTonalButton(onClick = { chosenAction = action }, modifier = Modifier.weight(1f)) { Text(action.label, maxLines = 1) }
                                } else {
                                    OutlinedButton(onClick = { chosenAction = action }, modifier = Modifier.weight(1f)) { Text(action.label, maxLines = 1) }
                                }
                            }
                        }
                    }
                    Button(
                        enabled = !isWorking && !isDownloading,
                        onClick = {
                            when {
                                draft.isBlank() -> notice = "Add some text first."
                                !modelReady -> {
                                    notice = "Download the on-device model in AI settings first."
                                    showSetup = true
                                }
                                else -> scope.launch {
                                    isWorking = true
                                    notice = "Running Qwen locally on this device…"
                                    try {
                                        val result = OnDeviceQwenWritingAssistant(context).rewrite(draft, chosenAction)
                                        draft = result.text
                                        notice = "Done — processed on your device."
                                    } catch (error: Throwable) {
                                        notice = error.message ?: "Local AI failed. Try again."
                                    } finally {
                                        isWorking = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text(if (isWorking) "Improving…" else "Improve writing"); Spacer(Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.padding(5.dp).size(15.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { clipboard.setText(AnnotatedString(draft)); notice = "Copied to clipboard." }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Copy")
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showSetup) {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Psychology, contentDescription = null); Spacer(Modifier.width(8.dp))
                            Text("Make AI yours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Run writing improvements privately on this phone with Qwen3 0.6B (Apache-2.0). No API key or cloud account is needed. The model is downloaded separately (${QwenModelStore.MODEL_SIZE_LABEL}) and then works offline.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (isDownloading) "Downloading model… $modelProgress"
                            else if (modelReady) "Qwen3 is ready on this device. The first rewrite takes a few seconds while weights load."
                            else "Model status: $modelProgress",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            enabled = !isDownloading && !isWorking && !modelReady,
                            onClick = {
                                scope.launch {
                                    isDownloading = true
                                    progressFlow.value = "Starting download"
                                    try {
                                        QwenModelStore.downloadModel(context) { downloaded, total ->
                                            progressFlow.value = if (total > 0L) {
                                                "${downloaded / 1_000_000} / ${total / 1_000_000} MB"
                                            } else "${downloaded / 1_000_000} MB downloaded"
                                        }
                                        modelReady = true
                                        progressFlow.value = "Downloaded"
                                        notice = "Qwen3 is ready. Writing improvements now run offline."
                                    } catch (error: Throwable) {
                                        progressFlow.value = error.message ?: "Download failed"
                                    } finally {
                                        isDownloading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isDownloading) "Downloading…" else if (modelReady) "Model downloaded" else "Download Qwen3 model")
                        }
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Open keyboard settings")
                        }
                        TextButton(onClick = { showSetup = false }) { Text("Dismiss") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard("Grammar", "Clearer, correct sentences", Modifier.weight(1f)) { Icon(Icons.Rounded.Spellcheck, contentDescription = null) }
                FeatureCard("Rewrite", "Find your best tone", Modifier.weight(1f)) { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) }
            }
            Text("Made for your voice · Private by design", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/**
 * Whether the WordCraft IME is currently enabled.
 *
 * Do **not** read Settings.Secure.ENABLED_INPUT_METHODS here: Android denies that key to apps
targeting SDK 34+ and throws SecurityException("Settings key: <enabled_input_methods> is only
readable to apps with targetSdkVersion lower than or equal to: 33"). That exception is thrown on
the first frame, so it looks like "the app crashes on open" — it was the startup crash on Android
14+ until this was replaced. InputMethodManager exposes the same information publicly.
 */
private fun isOurKeyboardEnabled(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    manager.enabledInputMethodList.any { it.packageName == context.packageName }
}.getOrDefault(false)

@Composable
private fun FeatureCard(title: String, subtitle: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Card(modifier = modifier, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
