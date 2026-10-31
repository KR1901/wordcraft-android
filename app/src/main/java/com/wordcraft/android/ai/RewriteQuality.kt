package com.wordcraft.android.ai

import kotlin.math.abs

/**
 * Guards that decide whether a rewrite is usable. Every one of these exists because a real model
 * produced the failure it detects:
 *
 *  - [isEcho]: "carry baggage" came back as "carry the baggage" — a two-comma edit sold as a
 *    rewrite. An absolute length tolerance let it through; the tolerance is now relative.
 *  - [isFragment]: a 698-character input came back as the single word "and".
 *  - [isDegenerate]: the model repeated the same paragraph three times.
 *  - [isCollapsed]: "same length" produced half the text, i.e. a summary.
 *
 * Pure functions, unit-tested on the JVM: see `RewriteQualityTest`.
 */
object RewriteQuality {

    fun isEcho(original: String, output: String): Boolean {
        val a = original.filter { it.isLetterOrDigit() }.lowercase()
        val b = output.filter { it.isLetterOrDigit() }.lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val wordsA = words(original)
        val wordsB = words(output)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size.toDouble() / maxOf(wordsA.size, wordsB.size)
        // Relative, not absolute: 5% of the length, never less than 8 characters.
        val lengthClose = abs(a.length - b.length) <= maxOf(8, a.length / 20)
        return overlap > 0.9 && lengthClose
    }

    fun isFragment(input: String, output: String): Boolean =
        input.length > 40 && output.length < input.length / 5

    fun isDegenerate(output: String): Boolean {
        val sentences = output.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 40 }
        if (sentences.size < 3) return false
        return sentences.groupingBy { it }.eachCount().values.any { it >= 2 }
    }

    fun isCollapsed(input: String, output: String, sameLengthRequested: Boolean): Boolean {
        if (!sameLengthRequested) return false
        val inChars = input.count { !it.isWhitespace() }
        val outChars = output.count { !it.isWhitespace() }
        if (inChars == 0) return false
        return outChars.toDouble() / inChars < 0.7
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
}
