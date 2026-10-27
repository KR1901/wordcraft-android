package com.wordcraft.android.ui

import android.content.Intent
import android.provider.Settings
import android.content.Context
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wordcraft.android.ai.OnDeviceQwenWritingAssistant
import com.wordcraft.android.ai.QwenModelStore
import com.wordcraft.android.ai.WritingAction
import kotlinx.coroutines.launch

@Composable
fun WordCraftApp() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val draftStore = remember(context) { context.getSharedPreferences("wordcraft_documents", 0) }
    var draft by remember { mutableStateOf(draftStore.getString("draft_default", "Your words, only clearer.") ?: "") }
    var chosenAction by remember { mutableStateOf(WritingAction.FIX_GRAMMAR) }
    var showSetup by remember { mutableStateOf(false) }
    val inputMethodManager = remember(context) { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    var keyboardEnabled by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        keyboardEnabled = enabled.split(':').any { it.contains(context.packageName) && it.contains("WordCraftInputMethodService") }
    }
    var notice by remember { mutableStateOf("Ready when you are.") }
    val scope = rememberCoroutineScope()
    // Status checks use QwenModelStore only — never loads LiteRT native code at startup.
    var modelReady by remember { mutableStateOf(QwenModelStore.isModelDownloaded(context)) }
    var isDownloading by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableStateOf("Not downloaded") }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
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
                        value = draft, onValueChange = { draft = it; draftStore.edit().putString("draft_default", it).apply() },
                        modifier = Modifier.fillMaxWidth().height(190.dp), shape = RoundedCornerShape(20.dp),
                        placeholder = { Text("Paste or write something…") },
                        supportingText = { Text("Your draft stays on this device until you choose an AI provider.") }
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
                            else if (modelReady) "Qwen3 is ready on this device."
                            else "Model status: $modelProgress",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            enabled = !isDownloading && !isWorking && !modelReady,
                            onClick = {
                                scope.launch {
                                    isDownloading = true
                                    modelProgress = "Starting download"
                                    try {
                                        QwenModelStore.downloadModel(context) { downloaded, total ->
                                            modelProgress = if (total > 0L) {
                                                "${downloaded / 1_000_000} / ${total / 1_000_000} MB"
                                            } else "${downloaded / 1_000_000} MB downloaded"
                                        }
                                        modelReady = true
                                        modelProgress = "Downloaded"
                                        notice = "Qwen3 is ready. Writing improvements now run offline."
                                    } catch (error: Throwable) {
                                        modelProgress = error.message ?: "Download failed"
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
