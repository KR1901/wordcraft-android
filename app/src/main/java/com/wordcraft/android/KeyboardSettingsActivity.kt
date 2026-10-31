package com.wordcraft.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wordcraft.android.keyboard.KeyboardPrefs
import com.wordcraft.android.keyboard.WordCraftKeyboardTheme
import com.wordcraft.android.keyboard.WordEngine

/** Settings for the keyboard. Every switch writes straight to [KeyboardPrefs]. */
class KeyboardSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = KeyboardPrefs(this)
        val engine = WordEngine(this)
        setContent {
            WordCraftKeyboardTheme(prefs.darkTheme) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Keyboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Applies to the WordCraft keyboard. Changes take effect the next time the keyboard opens.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SwitchRow("Key sound", prefs.soundEnabled) { prefs.soundEnabled = it; prefs.save() }
                            SwitchRow("Vibration", prefs.hapticsEnabled) { prefs.hapticsEnabled = it; prefs.save() }
                            SwitchRow("Number row", prefs.numberRow) { prefs.numberRow = it; prefs.save() }
                            SwitchRow("Suggestions", prefs.suggestionsEnabled) { prefs.suggestionsEnabled = it; prefs.save() }
                            SwitchRow("Autocorrect", prefs.autoCorrect) { prefs.autoCorrect = it; prefs.save() }
                            SwitchRow("Auto-capitalise", prefs.autoCapitalize) { prefs.autoCapitalize = it; prefs.save() }
                            SwitchRow("Double-space full stop", prefs.smartPunctuation) { prefs.smartPunctuation = it; prefs.save() }
                        }
                    }

                    Text("Key height", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.keyHeight == 0,
                            onClick = { prefs.chooseKeyHeight(0) },
                            label = { Text("Compact") }
                        )
                        FilterChip(
                            selected = prefs.keyHeight == 1,
                            onClick = { prefs.chooseKeyHeight(1) },
                            label = { Text("Normal") }
                        )
                        FilterChip(
                            selected = prefs.keyHeight == 2,
                            onClick = { prefs.chooseKeyHeight(2) },
                            label = { Text("Tall") }
                        )
                    }

                    Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.darkTheme == null,
                            onClick = { prefs.setTheme(null) },
                            label = { Text("System") }
                        )
                        FilterChip(
                            selected = prefs.darkTheme == false,
                            onClick = { prefs.setTheme(false) },
                            label = { Text("Light") }
                        )
                        FilterChip(
                            selected = prefs.darkTheme == true,
                            onClick = { prefs.setTheme(true) },
                            label = { Text("Dark") }
                        )
                    }

                    Text("Personal dictionary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Words you add here are always offered in the suggestion strip and are never autocorrected away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var draft by remember { mutableStateOf("") }
                    var userWords by remember { mutableStateOf(engine.userWords()) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("Add a word") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            engine.addUserWord(draft)
                            draft = ""
                            userWords = engine.userWords()
                        }) { Text("Add") }
                    }
                    userWords.forEach { word ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(word, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = {
                                engine.removeUserWord(word)
                                userWords = engine.userWords()
                            }) { Text("Remove") }
                        }
                    }

                    Text(
                        "Holding ⌫ deletes whole words, then clears the rest of the text quickly. " +
                            "Swipe left on ⌫ to delete word by word. Long-press Paste for clipboard " +
                            "history. Double-tap ⇧ for caps lock. Drag on the space bar to move the " +
                            "cursor. Long-press a key for accents and alternates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enable or choose the keyboard") }
                    OutlinedButton(
                        onClick = {
                            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            manager.showInputMethodPicker()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Switch keyboard") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
