package com.wordcraft.android.ai

import org.json.JSONArray

/**
 * Grammar corrections as spans, applied by the app.
 *
 * Small models cannot be trusted to regenerate a paragraph: they copy it, summarise it, or truncate
 * it. They *can* reliably list corrections, so that is all they are asked for. This object turns
 * their answer into edits and applies them to the original text, which makes content loss
 * structurally impossible — the worst case is that an edit is skipped.
 *
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM: see `GrammarEditsTest`.
 */
object GrammarEdits {

    data class Edit(val find: String, val replace: String)

    /** Parses the model's JSON edit list, tolerating code fences and surrounding prose. */
    fun parse(raw: String): List<Edit> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val array = JSONArray(raw.substring(start, end + 1))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val find = item.optString("find").takeIf { it.isNotBlank() } ?: continue
                    val replace = item.optString("replace", "")
                    // A no-op or an absurdly long span is noise, not a correction.
                    if (find != replace && find.length in 1..MAX_SPAN) add(Edit(find, replace))
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Applies each edit to the first occurrence of its span. Spans that are not found verbatim are
     * skipped, so a hallucinated edit can never damage the text.
     */
    fun apply(input: String, edits: List<Edit>): String {
        var text = input
        for (edit in edits) {
            val index = text.indexOf(edit.find)
            if (index < 0) continue
            text = text.substring(0, index) + edit.replace + text.substring(index + edit.find.length)
        }
        return text
    }

    /** Convenience for callers that only want the result. */
    fun applyAll(input: String, raw: String): Pair<String, List<Edit>> {
        val edits = parse(raw)
        return apply(input, edits) to edits
    }

    private const val MAX_SPAN = 400
}
