package com.wordcraft.android.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordcraft.android.KeyboardSettingsActivity
import com.wordcraft.android.MainActivity
import com.wordcraft.android.ai.OnDeviceQwenWritingAssistant
import com.wordcraft.android.ai.WritingAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Page { LETTERS, NUMBERS, SYMBOLS, EMOJI }
enum class Shift { OFF, ON, LOCKED }
enum class StripAction { INSERT, SUGGEST, UNDO, AI }

/** One chip in the strip above the keys. */
data class StripItem(val label: String, val value: String, val action: StripAction)

/** The magnified bubble shown above a key while it is held. */
data class KeyPreview(val label: String, val center: Offset)

/** Inline writing action state, rendered in the panel above the keys. */
sealed interface AiState {
    data object Idle : AiState
    data class Working(val action: String) : AiState
    data class Result(
        val action: String,
        val original: String,
        val rewritten: String,
        val hadSelection: Boolean
    ) : AiState
    data class Failure(val action: String, val message: String) : AiState
}

private enum class KeyStyle { Normal, Function, Accent }
private data class UndoState(val original: String, val inserted: String)

private const val MAX_INLINE_CHARS = 400
private const val CURSOR_STEP_PX = 26f
private const val DELETE_STEP_PX = 46f
private const val REPEAT_GRACE_MS = 260L
private const val WORD_REPEAT_MS = 45L
private const val BULK_REPEAT_MS = 26L
private const val BULK_CHARS = 60
private const val BULK_AFTER_TICKS = 10

/**
 * All keyboard behaviour lives here so the composables stay declarative: text entry, deletion
 * (tap / hold / word / swipe), shift and caps, suggestions, undo, clipboard history, learning,
 * cursor dragging, key previews, sound and haptics.
 */
class KeyboardController(
    private val service: InputMethodService,
    val prefs: KeyboardPrefs,
    private val engine: WordEngine,
) {
    var page by mutableStateOf(Page.LETTERS)
    var shift by mutableStateOf(Shift.OFF)
    var suggestions by mutableStateOf<List<StripItem>>(emptyList())
    var alternatives by mutableStateOf<List<StripItem>>(emptyList())
    var preview by mutableStateOf<KeyPreview?>(null)
    var aiState by mutableStateOf<AiState>(AiState.Idle)

    private var aiJob: Job? = null
    private var undoState by mutableStateOf<UndoState?>(null)
    private var rootOrigin = Offset.Zero
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var repeatJob: Job? = null
    private var lastShiftTapAt = 0L
    private var lastSpaceAt = 0L
    private var dragAccumulator = 0f
    private var deleteAccumulator = 0f
    private var hapticTarget: android.view.View? = null

    private val ic get() = service.currentInputConnection

    fun attachHapticTarget(view: android.view.View) {
        hapticTarget = view
    }

    // ---------------------------------------------------------------- lifecycle

    fun onStartInput() {
        page = Page.LETTERS
        alternatives = emptyList()
        preview = null
        shift = if (prefs.autoCapitalize && atSentenceStart()) Shift.ON else Shift.OFF
        refresh()
    }

    fun onFinishInput() {
        stopRepeat()
        alternatives = emptyList()
        suggestions = emptyList()
        preview = null
    }

    fun destroy() {
        stopRepeat()
        scope.cancel()
    }

    // ---------------------------------------------------------------- feedback

    fun keyDown() {
        if (prefs.hapticsEnabled) {
            runCatching { hapticTarget?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
        }
        if (prefs.soundEnabled) {
            runCatching {
                val audio = service.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audio.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD)
            }
        }
    }

    // ---------------------------------------------------------------- key preview

    fun setRootOrigin(origin: Offset) {
        rootOrigin = origin
    }

    fun showPreview(label: String, center: Offset) {
        preview = KeyPreview(label, center)
    }

    fun hidePreview() {
        preview = null
    }

    /** Preview centre in keyboard-root coordinates, ready for the bubble's offset. */
    val previewPosition: Offset
        get() = preview?.let { Offset(it.center.x - rootOrigin.x, it.center.y - rootOrigin.y) }
            ?: Offset.Zero

    // ---------------------------------------------------------------- typing

    fun typeLetter(ch: Char) {
        val text = if (shift == Shift.OFF) ch.toString() else ch.uppercaseChar().toString()
        ic?.commitText(text, 1)
        if (shift == Shift.ON) shift = Shift.OFF
        alternatives = emptyList()
        refresh()
    }

    fun typeSymbol(text: String) {
        ic?.commitText(text, 1)
        alternatives = emptyList()
        refresh()
    }

    fun typeEmoji(emoji: String) {
        ic?.commitText(emoji, 1)
        engine.rememberEmoji(emoji)
        refresh()
    }

    fun space() {
        val connection = ic ?: return
        val now = System.currentTimeMillis()
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (prefs.smartPunctuation && before.endsWith(" ") && now - lastSpaceAt < 450) {
            connection.deleteSurroundingText(1, 0)
            connection.commitText(". ", 1)
            lastSpaceAt = 0L
            refresh()
            return
        }
        val word = currentWord()
        val corrected = if (prefs.autoCorrect) engine.correctionFor(word) else null
        if (corrected != null) {
            connection.deleteSurroundingText(word.length, 0)
            connection.commitText("$corrected ", 1)
            engine.learnWord(corrected, previousWord())
            undoState = UndoState(original = word, inserted = "$corrected ")
        } else {
            connection.commitText(" ", 1)
            engine.learnWord(word, previousWord())
        }
        lastSpaceAt = now
        alternatives = emptyList()
        refresh()
    }

    fun enter() {
        val action = service.currentInputEditorInfo?.imeOptions
            ?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            service.sendDefaultEditorAction(true)
        } else {
            ic?.commitText("\n", 1)
            engine.learnWord(currentWord(), previousWord())
        }
        alternatives = emptyList()
        refresh()
    }

    fun applySuggestion(text: String) {
        val connection = ic ?: return
        val word = currentWord()
        if (word.isNotEmpty()) connection.deleteSurroundingText(word.length, 0)
        connection.commitText(text, 1)
        if (text.endsWith(" ")) engine.learnWord(text.trim(), previousWord())
        alternatives = emptyList()
        refresh()
    }

    fun paste() {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(service)?.toString()
        if (!text.isNullOrEmpty()) {
            ic?.commitText(text, 1)
            engine.rememberClipboard(text)
        }
        refresh()
    }

    /** Long-press on Paste: offer recent clipboard entries in the strip. */
    fun showClipboardHistory() {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(service)?.toString()?.trim()
        if (!current.isNullOrEmpty()) engine.rememberClipboard(current)
        val history = engine.clipboardHistory()
        if (history.isEmpty()) {
            alternatives = listOf(StripItem("Clipboard is empty", "", StripAction.UNDO))
            return
        }
        alternatives = history.take(3).map { entry ->
            StripItem(previewOf(entry), entry, StripAction.INSERT)
        }
    }

    fun undoLast() {
        val undo = undoState ?: return
        val connection = ic ?: return
        connection.deleteSurroundingText(undo.inserted.length, 0)
        connection.commitText(undo.original, 1)
        undoState = null
        refresh()
    }

    // ---------------------------------------------------------------- deleting

    fun deleteChar() {
        val connection = ic ?: return
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            connection.deleteSurroundingTextInCodePoints(1, 0)
        }
        refresh()
    }

    fun deleteWord() {
        val connection = ic ?: return
        val before = connection.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var cut = before.length
        while (cut > 0 && !before[cut - 1].isLetterOrDigit()) cut--
        while (cut > 0 && before[cut - 1].isLetterOrDigit()) cut--
        val count = before.length - cut
        if (count > 0) connection.deleteSurroundingTextInCodePoints(count, 0)
    }

    /** Swipe left across the backspace key: delete words at the speed of the gesture. */
    fun dragDelete(dx: Float) {
        deleteAccumulator += dx
        while (deleteAccumulator < -DELETE_STEP_PX) {
            deleteWord()
            deleteAccumulator += DELETE_STEP_PX
        }
    }

    fun endDragDelete() {
        deleteAccumulator = 0f
        refresh()
    }

    fun clearAll() {
        val connection = ic ?: return
        val before = connection.getTextBeforeCursor(20_000, 0)?.length ?: 0
        val after = connection.getTextAfterCursor(20_000, 0)?.length ?: 0
        if (before > 0 || after > 0) connection.deleteSurroundingText(before, after)
        refresh()
    }

    /**
     * Hold-to-delete. The first tick removes a character, then it removes whole words, and after a
     * moment it removes chunks — holding the key clears a long draft in about a second.
     */
    fun startHoldDelete() {
        stopRepeat()
        repeatJob = scope.launch {
            delay(REPEAT_GRACE_MS)
            var ticks = 0
            while (isActive) {
                if (ticks < BULK_AFTER_TICKS) {
                    deleteWord()
                    delay(WORD_REPEAT_MS)
                } else {
                    val connection = ic ?: break
                    val available = connection.getTextBeforeCursor(BULK_CHARS, 0)?.length ?: 0
                    if (available == 0) break
                    connection.deleteSurroundingText(available, 0)
                    delay(BULK_REPEAT_MS)
                }
                ticks++
            }
            refresh()
        }
    }

    fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    // ---------------------------------------------------------------- shift

    fun toggleShift() {
        val now = System.currentTimeMillis()
        shift = when {
            now - lastShiftTapAt < 300L -> if (shift == Shift.LOCKED) Shift.OFF else Shift.LOCKED
            shift == Shift.OFF -> Shift.ON
            shift == Shift.ON -> Shift.OFF
            else -> Shift.OFF
        }
        lastShiftTapAt = now
    }

    fun lockShift() {
        shift = if (shift == Shift.LOCKED) Shift.OFF else Shift.LOCKED
    }

    // ---------------------------------------------------------------- pages

    fun showPage(newPage: Page) {
        page = newPage
        alternatives = emptyList()
        if (newPage != Page.LETTERS) shift = Shift.OFF
    }

    // ---------------------------------------------------------------- cursor

    fun dragCursor(dx: Float) {
        dragAccumulator += dx
        while (dragAccumulator > CURSOR_STEP_PX) {
            moveCursor(KeyEvent.KEYCODE_DPAD_LEFT)
            dragAccumulator -= CURSOR_STEP_PX
        }
        while (dragAccumulator < -CURSOR_STEP_PX) {
            moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT)
            dragAccumulator += CURSOR_STEP_PX
        }
    }

    private fun moveCursor(keyCode: Int) {
        val connection = ic ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ---------------------------------------------------------------- strip

    fun showAlternatives(options: List<String>) {
        alternatives = options.map { StripItem(it, it, StripAction.INSERT) }
    }

    fun stripItems(): List<StripItem> {
        val base = if (alternatives.isNotEmpty()) alternatives else suggestions
        val undo = if (undoState != null) listOf(StripItem("↶ Undo", "", StripAction.UNDO)) else emptyList()
        val limit = if (base.firstOrNull()?.action == StripAction.AI) 20 else 3
        return (undo + base).take(limit)
    }

    fun onStripItem(item: StripItem) {
        when (item.action) {
            StripAction.UNDO -> {
                if (item.label == "↶ Undo") {
                    undoLast()
                } else {
                    alternatives = emptyList()
                }
            }
            StripAction.INSERT -> {
                if (item.value.isNotEmpty()) ic?.commitText(item.value, 1)
                alternatives = emptyList()
                refresh()
            }
            StripAction.SUGGEST -> applySuggestion(item.value)
            StripAction.AI -> {
                alternatives = emptyList()
                WritingAction.entries.firstOrNull { it.label == item.label }?.let { runInlineAction(it) }
            }
        }
    }

    // ---------------------------------------------------------------- intents

    fun openSettings() {
        runCatching {
            service.startActivity(
                Intent(service, KeyboardSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openStudio() {
        runCatching {
            service.startActivity(
                Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Runs a writing action on the selection (or the text before the cursor) **inside the keyboard**
     * and shows the result in the panel above the keys. Nothing is launched and nothing leaves the
     * device: the model runs on-device.
     */
    fun runInlineAction(action: WritingAction) {
        val connection = ic ?: return
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        val hadSelection = selected.isNotBlank()
        val original = if (hadSelection) {
            selected
        } else {
            connection.getTextBeforeCursor(MAX_INLINE_CHARS, 0)?.toString().orEmpty().trimEnd()
        }
        if (original.isBlank()) {
            aiState = AiState.Failure(action.label, "Type or select some text first.")
            return
        }
        aiJob?.cancel()
        aiState = AiState.Working(action.label)
        aiJob = scope.launch {
            try {
                val result = OnDeviceQwenWritingAssistant(service).rewrite(original, action)
                aiState = AiState.Result(action.label, original, result.text.trim(), hadSelection)
            } catch (error: Throwable) {
                aiState = AiState.Failure(action.label, error.message ?: "On-device rewrite failed.")
            }
        }
    }

    fun applyAiResult() {
        val state = aiState as? AiState.Result ?: return
        val connection = ic ?: return
        // With a selection the commit replaces it; otherwise remove what we rewrote.
        if (!state.hadSelection) connection.deleteSurroundingText(state.original.length, 0)
        connection.commitText(state.rewritten, 1)
        aiState = AiState.Idle
        refresh()
    }

    fun dismissAi() {
        aiJob?.cancel()
        aiJob = null
        aiState = AiState.Idle
    }

    /** The tone picker: every writing action as a chip in the strip. */
    fun showToneStrip() {
        aiJob?.cancel()
        aiState = AiState.Idle
        alternatives = WritingAction.entries.map { StripItem(it.label, "", StripAction.AI) }
    }

    fun recentEmojis(): List<String> = engine.recentEmojis()

    // ---------------------------------------------------------------- state

    fun refresh() {
        // Any text edit invalidates a pending or shown rewrite: its offsets would be stale.
        if (aiState != AiState.Idle) dismissAi()
        val word = currentWord()
        suggestions = if (prefs.suggestionsEnabled) {
            engine.suggest(word, previousWord()).map { StripItem(it, it, StripAction.SUGGEST) }
        } else {
            emptyList()
        }
        if (prefs.autoCapitalize && shift == Shift.OFF && atSentenceStart()) {
            shift = Shift.ON
        }
    }

    private fun previewOf(text: String): String =
        if (text.length <= 16) text else text.take(15) + "…"

    private fun textBefore(limit: Int = 96): String =
        ic?.getTextBeforeCursor(limit, 0)?.toString().orEmpty()

    private fun currentWord(): String =
        textBefore().takeLastWhile { it.isLetter() || it == '\'' }

    private fun previousWord(): String? {
        val before = textBefore()
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        val rest = before.dropLast(word.length).trimEnd()
        val previous = rest.takeLastWhile { it.isLetter() || it == '\'' }
        return previous.ifEmpty { null }
    }

    private fun atSentenceStart(): Boolean {
        val before = textBefore(4)
        if (before.isEmpty()) return true
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return true
        val last = trimmed.last()
        return last == '.' || last == '!' || last == '?' || last == '\n'
    }
}

// -------------------------------------------------------------------- view

@Composable
fun WordCraftKeyboard(controller: KeyboardController) {
    WordCraftKeyboardTheme(controller.prefs.darkTheme) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Box(Modifier.onGloballyPositioned { controller.setRootOrigin(it.boundsInRoot().topLeft) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp)) {
                    ToolbarRow(controller)
                    if (controller.aiState != AiState.Idle) {
                        AiPanel(controller)
                    } else {
                        StripRow(controller)
                    }
                    when (controller.page) {
                        Page.EMOJI -> EmojiPad(controller)
                        else -> KeyPad(controller)
                    }
                }
                controller.preview?.let { key ->
                    val position = controller.previewPosition
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (position.x - 26.dp.toPx()).toInt(),
                                    (position.y - 76.dp.toPx()).toInt()
                                )
                            }
                            .width(52.dp)
                            .height(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            key.label,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarRow(controller: KeyboardController) {
    Row(
        Modifier.fillMaxWidth().height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolChip("WordCraft", controller, accent = true, weight = 1.2f) { controller.openStudio() }
        ToolChip("✨ Tone", controller, weight = 1.1f) { controller.showToneStrip() }
        ToolChip("Fix", controller, weight = 0.8f) { controller.runInlineAction(WritingAction.FIX_GRAMMAR) }
        ToolChip("Paste", controller, weight = 0.9f, onLong = { controller.showClipboardHistory() }) {
            controller.paste()
        }
        ToolChip("Aa", controller, weight = 0.7f) { controller.openSettings() }
    }
}

@Composable
private fun RowScope.ToolChip(
    label: String,
    controller: KeyboardController,
    weight: Float,
    accent: Boolean = false,
    onLong: (() -> Unit)? = null,
    onTap: () -> Unit
) {
    val background = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val foreground = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .weight(weight)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        controller.keyDown()
                        if (tryAwaitRelease()) onTap()
                    },
                    onLongPress = { onLong?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StripRow(controller: KeyboardController) {
    val items = controller.stripItems()
    if (items.isEmpty()) {
        Row(Modifier.fillMaxWidth().height(36.dp)) { }
        return
    }
    if (items.size <= 3) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { item -> StripChip(item, controller, Modifier.weight(1f)) }
        }
    } else {
        // The tone picker has thirteen entries: scroll horizontally instead of squeezing them.
        Row(
            Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item -> StripChip(item, controller, Modifier.width(116.dp)) }
        }
    }
}

@Composable
private fun StripChip(item: StripItem, controller: KeyboardController, modifier: Modifier) {
    Box(
        modifier = modifier
            .height(32.dp)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(item) {
                detectTapGestures(onPress = {
                    controller.keyDown()
                    if (tryAwaitRelease()) controller.onStripItem(item)
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            item.label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Result panel for an inline writing action: shown instead of the suggestion strip. */
@Composable
private fun AiPanel(controller: KeyboardController) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (val state = controller.aiState) {
            is AiState.Working -> Text(
                "${state.action} — running on this device…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is AiState.Result -> {
                Text(
                    state.rewritten,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill("Replace", controller, accent = true) { controller.applyAiResult() }
                    ActionPill("Dismiss", controller, accent = false) { controller.dismissAi() }
                }
            }
            is AiState.Failure -> {
                Text(
                    state.message,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error
                )
                Row { ActionPill("Dismiss", controller, accent = false) { controller.dismissAi() } }
            }
            AiState.Idle -> Unit
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    controller: KeyboardController,
    accent: Boolean,
    onTap: () -> Unit
) {
    val background = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val foreground = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .pointerInput(label) {
                detectTapGestures(onPress = {
                    controller.keyDown()
                    if (tryAwaitRelease()) onTap()
                })
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private data class KeySpec(
    val label: String,
    val weight: Float = 1f,
    val style: KeyStyle = KeyStyle.Normal,
    val fontSize: TextUnit = 18.sp,
    val bottom: Boolean = false,
    val onTap: () -> Unit,
    val onLong: (() -> Unit)? = null,
    val holdDelete: Boolean = false,
    val swipeDelete: Boolean = false,
    val isSpace: Boolean = false
)

/** Key heights tuned to Gboard's proportions; the user can pick the row height in settings. */
private fun KeyboardController.keyHeightDp(): Dp = when (prefs.keyHeight) {
    0 -> 40.dp
    2 -> 52.dp
    else -> 46.dp
}

@Composable
private fun KeyPad(controller: KeyboardController) {
    val rows = buildRows(controller)
    val keyHeight = controller.keyHeightDp()
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { spec ->
                    if (spec.isSpace) {
                        SpaceKey(
                            controller = controller,
                            modifier = Modifier.weight(spec.weight).height(keyHeight)
                        )
                    } else {
                        Key(
                            label = spec.label,
                            controller = controller,
                            modifier = Modifier.weight(spec.weight).height(keyHeight),
                            style = spec.style,
                            fontSize = spec.fontSize,
                            onTap = spec.onTap,
                            onLong = spec.onLong,
                            holdDelete = spec.holdDelete,
                            swipeDelete = spec.swipeDelete
                        )
                    }
                }
            }
        }
    }
}

private fun buildRows(controller: KeyboardController): List<List<KeySpec>> {
    val shifted = controller.shift != Shift.OFF
    fun letter(ch: Char) = KeySpec(
        label = if (shifted) ch.uppercaseChar().toString() else ch.toString(),
        onTap = { controller.typeLetter(ch) },
        onLong = accentOptions(ch)?.let { options -> { controller.showAlternatives(options) } }
    )
    val shiftLabel = if (controller.shift == Shift.LOCKED) "⇪" else "⇧"
    val shiftStyle = if (controller.shift == Shift.OFF) KeyStyle.Function else KeyStyle.Accent
    val backspace = KeySpec(
        "⌫",
        weight = 1.3f,
        style = KeyStyle.Function,
        onTap = { controller.deleteChar() },
        onLong = { controller.startHoldDelete() },
        holdDelete = true,
        swipeDelete = true
    )

    return when (controller.page) {
        Page.NUMBERS -> listOf(
            "1234567890".map { ch -> KeySpec(ch.toString(), onTap = { controller.typeSymbol(ch.toString()) }) },
            listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/").map { s ->
                KeySpec(s, onTap = { controller.typeSymbol(s) })
            },
            listOf(
                KeySpec("=\\<", weight = 1.3f, style = KeyStyle.Function, fontSize = 14.sp,
                    onTap = { controller.showPage(Page.SYMBOLS) }),
                KeySpec("\"", onTap = { controller.typeSymbol("\"") }),
                KeySpec("'", onTap = { controller.typeSymbol("'") }),
                KeySpec(":", onTap = { controller.typeSymbol(":") }),
                KeySpec(";", onTap = { controller.typeSymbol(";") }),
                KeySpec("!", onTap = { controller.typeSymbol("!") }),
                KeySpec("?", onTap = { controller.typeSymbol("?") }),
                backspace
            ),
            bottomRow(controller, "ABC", { controller.showPage(Page.LETTERS) })
        )
        Page.SYMBOLS -> listOf(
            listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { s ->
                KeySpec(s, onTap = { controller.typeSymbol(s) })
            },
            listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { s ->
                KeySpec(s, onTap = { controller.typeSymbol(s) })
            },
            listOf(
                KeySpec("?123", weight = 1.3f, style = KeyStyle.Function, fontSize = 14.sp,
                    onTap = { controller.showPage(Page.NUMBERS) }),
                KeySpec("<", onTap = { controller.typeSymbol("<") }),
                KeySpec(">", onTap = { controller.typeSymbol(">") }),
                KeySpec("[", onTap = { controller.typeSymbol("[") }),
                KeySpec("]", onTap = { controller.typeSymbol("]") }),
                KeySpec("_", onTap = { controller.typeSymbol("_") }),
                backspace
            ),
            bottomRow(controller, "ABC", { controller.showPage(Page.LETTERS) })
        )
        else -> buildList {
            if (controller.prefs.numberRow) {
                add("1234567890".map { ch -> KeySpec(ch.toString(), onTap = { controller.typeSymbol(ch.toString()) }) })
            }
            add("qwertyuiop".map { letter(it) })
            add("asdfghjkl".map { letter(it) })
            add(
                listOf(
                    KeySpec(shiftLabel, weight = 1.3f, style = shiftStyle, onTap = { controller.toggleShift() },
                        onLong = { controller.lockShift() })
                ) + "zxcvbnm".map { letter(it) } + listOf(backspace)
            )
            add(bottomRow(controller, "?123", { controller.showPage(Page.NUMBERS) }))
        }
    }
}

private fun bottomRow(
    controller: KeyboardController,
    numberLabel: String,
    numberAction: () -> Unit
): List<KeySpec> = listOf(
    KeySpec(numberLabel, weight = 1.4f, style = KeyStyle.Function, fontSize = 13.sp, bottom = true, onTap = numberAction),
    KeySpec("☺", weight = 1f, style = KeyStyle.Function, bottom = true,
        onTap = { controller.showPage(Page.EMOJI) }),
    KeySpec(",", weight = 1f, bottom = true, onTap = { controller.typeSymbol(",") },
        onLong = { controller.showAlternatives(listOf(";", ":", "-", "–")) }),
    KeySpec("space", weight = 4f, bottom = true, isSpace = true, onTap = { controller.space() }),
    KeySpec(".", weight = 1f, bottom = true, onTap = { controller.typeSymbol(".") },
        onLong = { controller.showAlternatives(listOf(",", "?", "!", "…")) }),
    KeySpec("↵", weight = 1.4f, style = KeyStyle.Accent, bottom = true, onTap = { controller.enter() })
)

@Composable
private fun Key(
    label: String,
    controller: KeyboardController,
    modifier: Modifier = Modifier,
    style: KeyStyle,
    fontSize: TextUnit,
    onTap: () -> Unit,
    onLong: (() -> Unit)?,
    holdDelete: Boolean,
    swipeDelete: Boolean = false
) {
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    var centerInRoot by remember { mutableStateOf(Offset.Zero) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "keyScale"
    )
    val base = when (style) {
        KeyStyle.Normal -> MaterialTheme.colorScheme.surfaceContainer
        KeyStyle.Function -> MaterialTheme.colorScheme.surfaceContainerHigh
        KeyStyle.Accent -> MaterialTheme.colorScheme.primary
    }
    val foreground = when (style) {
        KeyStyle.Accent -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .padding(1.5.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) base.copy(alpha = 0.72f) else base)
            .onGloballyPositioned { centerInRoot = it.boundsInRoot().center }
            .pointerInput(label, style) {
                detectTapGestures(
                    onPress = {
                        longFired = false
                        pressed = true
                        controller.keyDown()
                        if (style == KeyStyle.Normal) controller.showPreview(label, centerInRoot)
                        if (!holdDelete) onTap()
                        val released = tryAwaitRelease()
                        pressed = false
                        controller.hidePreview()
                        if (holdDelete) {
                            controller.stopRepeat()
                            // Released before the hold fired: treat it as a single delete.
                            if (released && !longFired) onTap()
                        }
                    },
                    onLongPress = {
                        longFired = true
                        onLong?.invoke()
                    }
                )
            }
            .then(
                if (swipeDelete) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { controller.endDragDelete() },
                            onDragCancel = { controller.endDragDelete() },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                controller.dragDelete(dragAmount)
                            }
                        )
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun SpaceKey(controller: KeyboardController, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "spaceScale"
    )
    Box(
        modifier = modifier
            .padding(1.5.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    controller.keyDown()
                    val released = tryAwaitRelease()
                    pressed = false
                    if (released) controller.space()
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pressed = true },
                    onDragEnd = { pressed = false },
                    onDragCancel = { pressed = false },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        controller.dragCursor(dragAmount)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text("space", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun EmojiPad(controller: KeyboardController) {
    val emojis = (controller.recentEmojis() + EMOJIS).distinct()
    Column(Modifier.fillMaxWidth()) {
        emojis.chunked(8).take(5).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .pointerInput(emoji) {
                                detectTapGestures(onPress = {
                                    controller.keyDown()
                                    if (tryAwaitRelease()) controller.typeEmoji(emoji)
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            Key(
                label = "ABC",
                controller = controller,
                modifier = Modifier.weight(1.4f).height(44.dp),
                style = KeyStyle.Function,
                fontSize = 13.sp,
                onTap = { controller.showPage(Page.LETTERS) },
                onLong = null,
                holdDelete = false
            )
            SpaceKey(controller = controller, modifier = Modifier.weight(4f).height(44.dp))
            Key(
                label = "⌫",
                controller = controller,
                modifier = Modifier.weight(1.4f).height(44.dp),
                style = KeyStyle.Function,
                fontSize = 18.sp,
                onTap = { controller.deleteChar() },
                onLong = { controller.startHoldDelete() },
                holdDelete = true
            )
        }
    }
}

private fun accentOptions(ch: Char): List<String>? = when (ch) {
    'a' -> listOf("à", "á", "â", "ä", "ã", "å")
    'e' -> listOf("è", "é", "ê", "ë")
    'i' -> listOf("ì", "í", "î", "ï")
    'o' -> listOf("ò", "ó", "ô", "ö", "õ", "ø")
    'u' -> listOf("ù", "ú", "û", "ü")
    'c' -> listOf("ç")
    'n' -> listOf("ñ")
    's' -> listOf("ß")
    'y' -> listOf("ÿ")
    else -> null
}

private val EMOJIS = listOf(
    "😊", "😂", "🥰", "😍", "😎", "🤔", "😅", "🙃",
    "👍", "🙏", "👏", "🤝", "💪", "✌️", "👌", "🫶",
    "❤️", "🔥", "✨", "🎉", "💯", "👀", "🌟", "💜",
    "😭", "😡", "😴", "🤯", "🥳", "😬", "🤗", "😇",
    "☕", "🍕", "🎧", "📱", "💻", "📷", "🎯", "🚀"
)
