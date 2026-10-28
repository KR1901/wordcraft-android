package com.wordcraft.android.keyboard

import android.content.Context
import java.util.Locale
import kotlin.math.abs

/**
 * Suggestion, correction and learning engine.
 *
 * There is deliberately no bundled dictionary yet (that arrives with glide typing, see
 * docs/KEYBOARD-ROADMAP.md). Instead the engine learns the words and word-to-word transitions the
 * user actually types, which is enough for useful next-word suggestions and safe typo fixes on a
 * device-local store. A candidate is only auto-applied when it is one edit away from a word the
 * user has already used several times, so autocorrect never invents vocabulary.
 */
class WordEngine(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("keyboard_learning", Context.MODE_PRIVATE)

    private val words = HashMap<String, Int>()
    private val bigrams = HashMap<String, Int>()
    private var loaded = false

    fun suggest(current: String, previous: String?, limit: Int = 3): List<String> {
        load()
        val out = LinkedHashSet<String>()
        if (current.isEmpty()) {
            if (previous != null) {
                val prefix = previous.lowercase(Locale.ROOT) + SEP
                bigrams.entries
                    .filter { it.key.startsWith(prefix) }
                    .sortedByDescending { it.value }
                    .take(limit)
                    .forEach { out += it.key.substring(prefix.length) }
            }
            return out.take(limit)
        }

        val lower = current.lowercase(Locale.ROOT)
        TYPOS[lower]?.let { if (it != lower) out += matchCase(current, it) }
        if (out.size < limit) {
            words.entries
                .asSequence()
                .filter { it.value >= 2 && it.key.length >= 2 && isOneEditApart(lower, it.key) }
                .sortedByDescending { it.value }
                .take(1)
                .forEach { out += matchCase(current, it.key) }
        }
        if (out.size < limit) {
            words.entries
                .asSequence()
                .filter { it.value >= 1 && it.key.length > lower.length && it.key.startsWith(lower) }
                .sortedByDescending { it.value }
                .take(limit)
                .forEach { out += matchCase(current, it.key) }
        }
        if (current.first().isLowerCase()) {
            out += current.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        out.remove(current)
        return out.take(limit)
    }

    /** A correction safe enough to apply automatically on space, or null. */
    fun correctionFor(word: String): String? {
        if (word.length < 3) return null
        load()
        val lower = word.lowercase(Locale.ROOT)
        TYPOS[lower]?.let { return matchCase(word, it) }
        val candidate = words.entries
            .asSequence()
            .filter { it.value >= 3 && it.key.length >= 3 && isOneEditApart(lower, it.key) }
            .maxByOrNull { it.value }
            ?.key
        return candidate?.let { matchCase(word, it) }
    }

    fun learnWord(word: String, previous: String?) {
        val clean = word.trim()
        if (clean.length < 2 || clean.any { !it.isLetter() && it != '\'' }) return
        load()
        val lower = clean.lowercase(Locale.ROOT)
        words[lower] = (words[lower] ?: 0) + 1
        if (previous != null) {
            val key = previous.lowercase(Locale.ROOT) + SEP + lower
            bigrams[key] = (bigrams[key] ?: 0) + 1
        }
        if (words.size > MAX_WORDS) prune()
        save()
    }

    // ------------------------------------------------------------ clipboard history

    fun rememberClipboard(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val updated = (listOf(trimmed) + clipboardHistory().filter { it != trimmed }).take(MAX_CLIPBOARD)
        prefs.edit().putString(KEY_CLIPBOARD, updated.joinToString("\u0002")).apply()
    }

    fun clipboardHistory(): List<String> = prefs.getString(KEY_CLIPBOARD, null)
        ?.split('\u0002')
        ?.filter { it.isNotBlank() }
        .orEmpty()

    // ------------------------------------------------------------ personal dictionary

    fun userWords(): List<String> = prefs.getString(KEY_USER_WORDS, null)
        ?.split('\n')
        ?.filter { it.isNotBlank() }
        .orEmpty()

    fun addUserWord(word: String) {
        val clean = word.trim().lowercase(Locale.ROOT)
        if (clean.length < 2) return
        load()
        val updated = (userWords() + clean).distinct()
        prefs.edit().putString(KEY_USER_WORDS, updated.joinToString("\n")).apply()
        // A personal word outranks anything learned, so it is offered and corrected first.
        words[clean] = maxOf(words[clean] ?: 0, USER_WORD_WEIGHT)
        save()
    }

    fun removeUserWord(word: String) {
        val clean = word.trim().lowercase(Locale.ROOT)
        val updated = userWords().filter { it != clean }
        prefs.edit().putString(KEY_USER_WORDS, updated.joinToString("\n")).apply()
        words.remove(clean)
        save()
    }

    fun recentEmojis(): List<String> = prefs.getString(KEY_EMOJI, null)
        ?.split('\u0001')
        ?.filter { it.isNotBlank() }
        .orEmpty()

    fun rememberEmoji(emoji: String) {
        val updated = (listOf(emoji) + recentEmojis().filter { it != emoji }).take(16)
        prefs.edit().putString(KEY_EMOJI, updated.joinToString("\u0001")).apply()
    }

    private fun prune() {
        val keep = words.entries.sortedByDescending { it.value }.take(MAX_WORDS / 2)
        words.clear()
        keep.forEach { (k, v) -> words[k] = v }
        val keepBigrams = bigrams.entries.sortedByDescending { it.value }.take(MAX_WORDS)
        bigrams.clear()
        keepBigrams.forEach { (k, v) -> bigrams[k] = v }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        prefs.getString(KEY_WORDS, null)?.let { raw ->
            raw.split('\n').forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 2) {
                    parts[1].toIntOrNull()?.let { count -> words[parts[0]] = count }
                }
            }
        }
        userWords().forEach { word ->
            words[word] = maxOf(words[word] ?: 0, USER_WORD_WEIGHT)
        }
        prefs.getString(KEY_BIGRAMS, null)?.let { raw ->
            raw.split('\n').forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 2) {
                    parts[1].toIntOrNull()?.let { count -> bigrams[parts[0]] = count }
                }
            }
        }
    }

    private fun save() {
        val wordText = words.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        val bigramText = bigrams.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        prefs.edit().putString(KEY_WORDS, wordText).putString(KEY_BIGRAMS, bigramText).apply()
    }

    private fun matchCase(typed: String, candidate: String): String = when {
        typed.isEmpty() -> candidate
        typed.first().isUpperCase() -> candidate.replaceFirstChar { it.titlecase(Locale.ROOT) }
        else -> candidate
    }

    private fun isOneEditApart(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        if (a == b) return false
        if (a.length == b.length) {
            var diff = 0
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    diff++
                    if (diff > 1) return false
                }
            }
            return diff == 1
        }
        val (shorter, longer) = if (a.length < b.length) a to b else b to a
        var i = 0
        var j = 0
        var skipped = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
            } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }

    private companion object {
        const val KEY_WORDS = "words"
        const val KEY_BIGRAMS = "bigrams"
        const val KEY_EMOJI = "emoji_recents"
        const val KEY_CLIPBOARD = "clipboard_history"
        const val KEY_USER_WORDS = "user_words"
        const val MAX_CLIPBOARD = 12
        const val USER_WORD_WEIGHT = 100
        const val SEP = '|'
        const val MAX_WORDS = 3000

        val TYPOS = mapOf(
            "teh" to "the", "adn" to "and", "nad" to "and", "dont" to "don't", "im" to "i'm",
            "wont" to "won't", "cant" to "can't", "recieve" to "receive", "thier" to "their",
            "becuase" to "because", "becasue" to "because", "wierd" to "weird", "youre" to "you're",
            "thats" to "that's", "ive" to "i've", "isnt" to "isn't", "doesnt" to "doesn't",
            "didnt" to "didn't", "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
            "havent" to "haven't", "hasnt" to "hasn't", "wasnt" to "wasn't", "werent" to "weren't",
            "definately" to "definitely", "seperate" to "separate", "occured" to "occurred",
            "untill" to "until", "wich" to "which", "goign" to "going", "jsut" to "just",
            "taht" to "that", "waht" to "what", "yoou" to "you", "pls" to "please", "msg" to "message"
        )
    }
}
