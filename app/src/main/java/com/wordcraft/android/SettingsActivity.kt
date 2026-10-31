package com.wordcraft.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wordcraft.android.ai.LocalModel
import com.wordcraft.android.ai.QwenModelStore
import com.wordcraft.android.keyboard.KeyboardPrefs
import com.wordcraft.android.ui.theme.WordCraftTheme
import kotlinx.coroutines.launch

/**
 * The app's settings: pick and manage the on-device model, reach the keyboard settings, and see
 * what the app is. Models live here rather than inside the studio so the writing screen stays
 * about writing.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WordCraftTheme {
                SettingsScreen(onOpenKeyboard = {
                    startActivity(Intent(this, KeyboardSettingsActivity::class.java))
                })
            }
        }
    }
}

@Composable
private fun SettingsScreen(onOpenKeyboard: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(QwenModelStore.active(context)) }
    var downloaded by remember { mutableStateOf(QwenModelStore.downloadedModels(context)) }
    var downloading by remember { mutableStateOf<LocalModel?>(null) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    // Retired models (Qwen3, Qwen2.5) leave their files behind; clear them on entry.
    LaunchedEffect(Unit) {
        QwenModelStore.pruneUnknownModels(context)
        active = QwenModelStore.active(context)
        downloaded = QwenModelStore.downloadedModels(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Everything runs on this phone. A model is downloaded once and then works offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        LocalModel.entries.forEach { model ->
            val isDownloaded = downloaded.contains(model)
            val isActive = active == model
            val isDownloading = downloading == model
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    }
                )
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            model.sizeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        if (isActive && isDownloaded) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = "Active",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        model.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when {
                        isDownloading -> Text(
                            "Downloading… $progress",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        isDownloaded -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isActive) {
                                FilledTonalButton(onClick = {
                                    QwenModelStore.setActive(context, model)
                                    active = model
                                }) { Text("Use this model") }
                            }
                            TextButton(onClick = {
                                QwenModelStore.delete(context, model)
                                downloaded = QwenModelStore.downloadedModels(context)
                                if (active == model) {
                                    val next = downloaded.firstOrNull() ?: LocalModel.QWEN35_08B
                                    QwenModelStore.setActive(context, next)
                                    active = next
                                }
                                message = "${model.label} removed."
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Delete")
                            }
                        }
                        else -> Button(
                            enabled = downloading == null,
                            onClick = {
                                downloading = model
                                progress = "starting"
                                message = null
                                scope.launch {
                                    try {
                                        QwenModelStore.downloadModel(context, model) { done, total ->
                                            progress = if (total > 0L) {
                                                "${done / 1_000_000} / ${total / 1_000_000} MB"
                                            } else "${done / 1_000_000} MB"
                                        }
                                        QwenModelStore.setActive(context, model)
                                        active = model
                                        downloaded = QwenModelStore.downloadedModels(context)
                                        message = "${model.label} is ready and selected."
                                    } catch (error: Throwable) {
                                        message = error.message ?: "Download failed."
                                    } finally {
                                        downloading = null
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Download ${model.sizeLabel}")
                        }
                    }
                }
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        Text("Keyboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onOpenKeyboard, modifier = Modifier.fillMaxWidth()) {
            Text("Keyboard settings — sounds, height, dictionary, tones")
        }

        Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "WordCraft ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Writing assistant and keyboard. Models run locally through LiteRT-LM; your text " +
                        "is never uploaded. All models listed here are Apache-2.0 builds from the LiteRT "
                        + "community on Hugging Face, and every download link is checked to be public.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Active model: ${active.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
