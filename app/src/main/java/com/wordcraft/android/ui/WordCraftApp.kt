package com.wordcraft.android.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wordcraft.android.ai.WritingAction

@Composable
fun WordCraftApp() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var draft by remember { mutableStateOf("Your words, only clearer.") }
    var chosenAction by remember { mutableStateOf(WritingAction.FIX_GRAMMAR) }
    var showSetup by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("Ready when you are.") }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null) },
                text = { Text("Enable keyboard") }
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
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().height(190.dp), shape = RoundedCornerShape(20.dp),
                        placeholder = { Text("Paste or write something…") },
                        supportingText = { Text("Your draft stays on this device until you choose an AI provider.") }
                    )
                    Text("Improve your writing", style = MaterialTheme.typography.titleSmall)
                    val rows = listOf(
                        listOf(WritingAction.FIX_GRAMMAR, WritingAction.PARAPHRASE),
                        listOf(WritingAction.PROFESSIONAL, WritingAction.FRIENDLY),
                        listOf(WritingAction.CONCISE, WritingAction.SIMPLIFY)
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
                        onClick = {
                            notice = if (draft.isBlank()) "Add some text first." else
                                "${chosenAction.label} selected. Connect an AI provider in Settings to run it."
                            showSetup = true
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text("Improve writing"); Spacer(Modifier.weight(1f))
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
                        Text("AI is not configured yet. For production, connect a secure HTTPS backend that stores provider credentials server-side; never bundle a private API key in the APK.",
                            style = MaterialTheme.typography.bodyMedium)
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
