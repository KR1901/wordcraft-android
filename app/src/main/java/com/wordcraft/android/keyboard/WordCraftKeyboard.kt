package com.wordcraft.android.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodService
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import com.wordcraft.android.KeyboardSettingsActivity
import com.wordcraft.android.MainActivity
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
private enum class KeyStyle { Normal, Function, Accent }

private const val CURSOR_STEP_PX = 26f
private const val REPEAT_GRACE_MS = 260L
private const val WORD_REPEAT_MS = 45L
private const val BULK_REPEAT_MS = 26L
private const val BULK_CHARS = 60
private const val BULK_AFTER_TICKS = 10

/**
 * All keyboard behaviour lives here so the composables stay declarative: text entry, deletion
 * (tap / hold / word / clear), shift and caps, suggestions, learning, cursor dragging, sound and
 * haptics. The view is rebuilt by Compose; this object survives between input views.
 */
class KeyboardController(
    private val service: InputMethodService,
    val prefs: KeyboardPrefs,
    private val engine: WordEngine,
) {
    var page by mutableStateOf(Page.LETTERS)
    var shift by mutableStateOf(Shift.OFF)
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var alternatives by mutableStateOf<List<String>>(emptyList())
    var lastKeyWasSpace by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var repeatJob: Job? = null
    private var lastShiftTapAt = 0L
    private var lastSpaceAt = 0L
    private var dragAccumulator = 0f
    private var hapticTarget: android.view.View? = null

    private val ic get() = service.currentInputConnection

    fun attachHapticTarget(view: android.view.View) {
        hapticTarget = view
    }

    // ---------------------------------------------------------------- lifecycle

    fun onStartInput() {
        page = Page.LETTERS
        alternatives = emptyList()
        shift = if (prefs.autoCapitalize && atSentenceStart()) Shift.ON else Shift.OFF
        refresh()
    }

    fun onFinishInput() {
        stopRepeat()
        alternatives = emptyList()
        suggestions = emptyList()
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
        when {
            corrected != null -> {
                connection.deleteSurroundingText(word.length, 0)
                connection.commitText("$corrected ", 1)
                engine.learnWord(corrected, previousWord())
            }
            else -> {
                connection.commitText(" ", 1)
                engine.learnWord(word, previousWord())
            }
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
        if (!text.isNullOrEmpty()) ic?.commitText(text, 1)
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

    fun setPage(newPage: Page) {
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

    fun showAlternatives(options: List<String>) {
        alternatives = options
    }

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
     * Hands the selection (or the text before the cursor) to the studio with the requested action
     * preselected. The text is also copied, because the studio may start before the extras arrive.
     */
    fun runWritingAction(actionLabel: String) {
        val connection = ic ?: return
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        val text = if (selected.isNotBlank()) {
            selected
        } else {
            connection.getTextBeforeCursor(300, 0)?.toString().orEmpty()
        }
        if (text.isBlank()) return
        runCatching {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("WordCraft draft", text))
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_KEYBOARD_ACTION, actionLabel)
                    .putExtra(MainActivity.EXTRA_KEYBOARD_TEXT, text)
            )
        }
    }

    fun typeEmoji(emoji: String) {
        ic?.commitText(emoji, 1)
        engine.rememberEmoji(emoji)
        refresh()
    }

    fun recentEmojis(): List<String> = engine.recentEmojis()

    // ---------------------------------------------------------------- state

    fun refresh() {
        val word = currentWord()
        suggestions = if (prefs.suggestionsEnabled && word.isNotEmpty()) {
            engine.suggest(word, previousWord())
        } else if (prefs.suggestionsEnabled && word.isEmpty()) {
            engine.suggest("", previousWord()).take(3)
        } else {
            emptyList()
        }
        if (prefs.autoCapitalize && shift == Shift.OFF && atSentenceStart()) {
            shift = Shift.ON
        }
    }

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
            Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 5.dp)) {
                ToolbarRow(controller)
                StripRow(controller)
                when (controller.page) {
                    Page.EMOJI -> EmojiPad(controller)
                    else -> KeyPad(controller)
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
        ToolChip("WordCraft", controller, accent = true, weight = 1.4f) { controller.openStudio() }
        ToolChip("Grammar", controller, weight = 1f) { controller.runWritingAction("Fix grammar") }
        ToolChip("Rewrite", controller, weight = 1f) { controller.runWritingAction("Paraphrase") }
        ToolChip("Paste", controller, weight = 0.9f) { controller.paste() }
        ToolChip("Aa", controller, weight = 0.7f) { controller.openSettings() }
    }
}

@Composable
private fun RowScope.ToolChip(
    label: String,
    controller: KeyboardController,
    weight: Float,
    accent: Boolean = false,
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
                detectTapGestures(onPress = {
                    controller.keyDown()
                    if (tryAwaitRelease()) onTap()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StripRow(controller: KeyboardController) {
    val items = controller.alternatives.ifEmpty { controller.suggestions }
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        if (items.isEmpty()) {
            Text(
                text = "",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        } else {
            items.take(3).forEach { item ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .pointerInput(item) {
                            detectTapGestures(onPress = {
                                controller.keyDown()
                                if (tryAwaitRelease()) {
                                    if (controller.alternatives.isNotEmpty()) {
                                        controller.typeSymbol(item)
                                    } else {
                                        controller.applySuggestion(item)
                                    }
                                }
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        item,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
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
    val isSpace: Boolean = false
)

@Composable
private fun KeyPad(controller: KeyboardController) {
    val rows = buildRows(controller)
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { spec ->
                    if (spec.isSpace) {
                        SpaceKey(
                            controller = controller,
                            modifier = Modifier.weight(spec.weight).height(44.dp)
                        )
                    } else {
                        Key(
                            label = spec.label,
                            controller = controller,
                            modifier = Modifier.weight(spec.weight).height(if (spec.bottom) 44.dp else 46.dp),
                            style = spec.style,
                            fontSize = spec.fontSize,
                            onTap = spec.onTap,
                            onLong = spec.onLong,
                            holdDelete = spec.holdDelete
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
    val shiftLabel = when (controller.shift) {
        Shift.LOCKED -> "⇪"
        Shift.ON -> "⇧"
        Shift.OFF -> "⇧"
    }
    val shiftStyle = if (controller.shift == Shift.OFF) KeyStyle.Function else KeyStyle.Accent

    return when (controller.page) {
        Page.NUMBERS -> listOf(
            "1234567890".map { ch -> KeySpec(ch.toString(), onTap = { controller.typeSymbol(ch.toString()) }) },
            listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/").map { s ->
                KeySpec(s, onTap = { controller.typeSymbol(s) })
            },
            listOf(
                KeySpec("=\\<", weight = 1.3f, style = KeyStyle.Function, fontSize = 14.sp,
                    onTap = { controller.setPage(Page.SYMBOLS) }),
                KeySpec("\"", onTap = { controller.typeSymbol("\"") }),
                KeySpec("'", onTap = { controller.typeSymbol("'") }),
                KeySpec(":", onTap = { controller.typeSymbol(":") }),
                KeySpec(";", onTap = { controller.typeSymbol(";") }),
                KeySpec("!", onTap = { controller.typeSymbol("!") }),
                KeySpec("?", onTap = { controller.typeSymbol("?") }),
                KeySpec("⌫", weight = 1.3f, style = KeyStyle.Function, onTap = { controller.deleteChar() },
                    onLong = { controller.startHoldDelete() }, holdDelete = true)
            ),
            bottomRow(controller, "ABC", { controller.setPage(Page.LETTERS) })
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
                    onTap = { controller.setPage(Page.NUMBERS) }),
                KeySpec("<", onTap = { controller.typeSymbol("<") }),
                KeySpec(">", onTap = { controller.typeSymbol(">") }),
                KeySpec("[", onTap = { controller.typeSymbol("[") }),
                KeySpec("]", onTap = { controller.typeSymbol("]") }),
                KeySpec("_", onTap = { controller.typeSymbol("_") }),
                KeySpec("⌫", weight = 1.3f, style = KeyStyle.Function, onTap = { controller.deleteChar() },
                    onLong = { controller.startHoldDelete() }, holdDelete = true)
            ),
            bottomRow(controller, "ABC", { controller.setPage(Page.LETTERS) })
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
                ) + "zxcvbnm".map { letter(it) } + listOf(
                    KeySpec("⌫", weight = 1.3f, style = KeyStyle.Function, onTap = { controller.deleteChar() },
                        onLong = { controller.startHoldDelete() }, holdDelete = true)
                )
            )
            add(bottomRow(controller, "?123", { controller.setPage(Page.NUMBERS) }))
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
        onTap = { controller.setPage(Page.EMOJI) }),
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
    holdDelete: Boolean
) {
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
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
            .padding(2.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) base.copy(alpha = 0.72f) else base)
            .pointerInput(label, style) {
                detectTapGestures(
                    onPress = {
                        longFired = false
                        pressed = true
                        controller.keyDown()
                        if (!holdDelete) onTap()
                        val released = tryAwaitRelease()
                        pressed = false
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
            },
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
            .padding(2.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
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
    val recents = controller.recentEmojis()
    val emojis = (recents + EMOJIS).distinct()
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
                onTap = { controller.setPage(Page.LETTERS) },
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
