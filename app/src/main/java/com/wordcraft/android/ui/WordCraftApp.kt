package com.wordcraft.android.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.wordcraft.android.ai.LengthTarget
import com.wordcraft.android.ai.LocalModel
import com.wordcraft.android.ai.OnDeviceQwenWritingAssistant
import com.wordcraft.android.ai.QwenModelStore
import com.wordcraft.android.ai.WritingAction
import kotlinx.coroutines.launch

private enum class ChatRole { USER, ASSISTANT }

private data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val meta: String? = null,
    val failed: Boolean = false
)

/**
 * Chat-shaped writing studio.
 *
 * Two modes share one composer: pick a tone and the message is rewritten with it, or pick
 * "Chat" and it is sent to the model as a free prompt. Model, tone and length are all pickers in
 * the composer, so nothing hides behind a settings screen mid-task.
 */
@Composable
fun WordCraftApp(
    resumeTick: Int = 0,
    initialText: String? = null,
    initialAction: WritingAction? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var input by remember { mutableStateOf(initialText.orEmpty()) }
    var tone by remember { mutableStateOf<WritingAction?>(initialAction ?: WritingAction.FIX_GRAMMAR) }
    var length by remember { mutableStateOf(LengthTarget.SAME) }
    var model by remember { mutableStateOf(QwenModelStore.active(context)) }
    var modelReady by remember { mutableStateOf(QwenModelStore.isModelDownloaded(context)) }
    var busy by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var toneMenu by remember { mutableStateOf(false) }
    var lengthMenu by remember { mutableStateOf(false) }
    var keyboardEnabled by remember { mutableStateOf(false) }
    var crashReport by remember(context) { mutableStateOf(CrashReport.latest(context)) }

    LaunchedEffect(resumeTick) {
        keyboardEnabled = isOurKeyboardEnabled(context)
        model = QwenModelStore.active(context)
        modelReady = QwenModelStore.isModelDownloaded(context)
    }
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        val action = tone
        messages = messages + ChatMessage(ChatRole.USER, text)
        input = ""
        busy = true
        scope.launch {
            val assistant = OnDeviceQwenWritingAssistant(context)
            val meta = if (action == null) {
                "Chat · ${model.label}"
            } else {
                "${action.label} · ${if (action.allowsLengthTarget) length.label else "same length"} · ${model.label}"
            }
            try {
                val result = if (action == null) assistant.ask(text) else assistant.rewrite(text, action, length)
                messages = messages + ChatMessage(ChatRole.ASSISTANT, result.text, meta)
            } catch (error: Throwable) {
                messages = messages + ChatMessage(
                    ChatRole.ASSISTANT,
                    error.message ?: "On-device writing failed.",
                    meta,
                    failed = true
                )
            } finally {
                busy = false
            }
        }
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(insets)
                .imePadding()
        ) {
            // ------------------------------------------------------------ top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("WordCraft", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Box {
                        TextButton(onClick = { modelMenu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(
                                "${model.label}${if (modelReady) "" else " · not downloaded"} ▾",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            LocalModel.entries.forEach { entry ->
                                val downloaded = QwenModelStore.isModelDownloaded(context, entry)
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(entry.label, fontWeight = FontWeight.Medium)
                                            Text(
                                                if (downloaded) entry.sizeLabel + " · downloaded"
                                                else entry.sizeLabel + " · download in settings",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        if (entry == model && downloaded) {
                                            Icon(Icons.Rounded.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        modelMenu = false
                                        if (downloaded) {
                                            QwenModelStore.setActive(context, entry)
                                            model = entry
                                            modelReady = true
                                        } else {
                                            context.startActivity(Intent(context, SettingsActivity::class.java))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = {
                    if (keyboardEnabled) {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    } else {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                }) {
                    Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard")
                }
                IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }

            crashReport?.let { report ->
                CrashBanner(
                    text = report.text,
                    onCopy = { clipboard.setText(AnnotatedString(report.text)) },
                    onDismiss = {
                        CrashReport.dismiss(context, report)
                        crashReport = null
                    }
                )
            }

            // ------------------------------------------------------------ conversation
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item { WelcomeCard(onExample = { input = it }) }
                }
                items(messages) { message ->
                    MessageBubble(
                        message = message,
                        onCopy = {
                            clipboard.setText(AnnotatedString(message.text))
                        },
                        onUseAsInput = {
                            input = message.text
                            tone = null
                        }
                    )
                }
                if (busy) {
                    item { TypingBubble() }
                }
            }

            // ------------------------------------------------------------ composer
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            TextButton(onClick = { toneMenu = true }) {
                                Text(
                                    tone?.label ?: "Chat",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            DropdownMenu(expanded = toneMenu, onDismissRequest = { toneMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Chat — send as a prompt") },
                                    onClick = { tone = null; toneMenu = false }
                                )
                                WritingAction.entries.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.label) },
                                        leadingIcon = {
                                            if (tone == action) Icon(Icons.Rounded.Check, contentDescription = null)
                                        },
                                        onClick = { tone = action; toneMenu = false }
                                    )
                                }
                            }
                        }
                        if (tone?.allowsLengthTarget == true) {
                            Box {
                                TextButton(onClick = { lengthMenu = true }) {
                                    Text(length.label, style = MaterialTheme.typography.labelLarge)
                                }
                                DropdownMenu(expanded = lengthMenu, onDismissRequest = { lengthMenu = false }) {
                                    LengthTarget.entries.forEach { target ->
                                        DropdownMenuItem(
                                            text = { Text(target.label) },
                                            leadingIcon = {
                                                if (length == target) Icon(Icons.Rounded.Check, contentDescription = null)
                                            },
                                            onClick = { length = target; lengthMenu = false }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (!modelReady) {
                            Text(
                                "No model",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp),
                            shape = RoundedCornerShape(22.dp),
                            placeholder = {
                                Text(if (tone == null) "Ask anything…" else "Write or paste, then send")
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { send() },
                            enabled = !busy && input.isNotBlank(),
                            modifier = Modifier.size(52.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (input.isNotBlank() && !busy) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send",
                                tint = if (input.isNotBlank() && !busy) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeCard(onExample: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A little more you.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Pick a tone and send text to rewrite it, or choose Chat and prompt the model " +
                    "directly. Everything runs on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onExample("hey can u send me the file when u get a chance") }) {
                Text("Try an example")
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onUseAsInput: () -> Unit
) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (isUser) 22.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 22.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.failed -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.meta?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.failed) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Row {
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                    if (!isUser && !message.failed) {
                        TextButton(onClick = onUseAsInput) { Text("Use as prompt") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Text(
                "Running on this device…",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CrashBanner(text: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Previous session crashed",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 8
            )
            Row {
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
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
