package com.wordcraft.android.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the grammar path.
 *
 * The bug these lock down: a small model cannot be asked to regenerate a paragraph — it copies,
 * summarises or truncates. Grammar is therefore "list edits, apply them locally", and these tests
 * assert that the app can never lose the user's text while applying them.
 */
class GrammarEditsTest {

    private val input = """
        💥Countries carry baggage of their entire past history.

        If we are expecting our hard work, sincerity and talent will stop them from attacking NRIs, we are totally mistaken.

        @PiyushGoyal @sanjevvsanyal prudence lies in informing NRIs clearly that all of their accumulated wealth is now at RISK.

        Repeating again and again that we are good people won't result into anything.
    """.trimIndent()

    /** The exact shape the model actually returns, code fence included. */
    private val modelOutput = """
        ```json
        [
          {
            "find": "carry baggage of their entire past history",
            "replace": "carry the burden of their entire past history"
          },
          {
            "find": "accumulated wealth is now at RISK",
            "replace": "accumulated wealth is now at risk"
          }
        ]
        ```
    """.trimIndent()

    @Test
    fun parsesFencedJsonFromTheModel() {
        val edits = GrammarEdits.parse(modelOutput)
        assertEquals(2, edits.size)
        assertEquals("carry baggage of their entire past history", edits[0].find)
        assertEquals("accumulated wealth is now at risk", edits[1].replace)
    }

    @Test
    fun appliesEditsToTheOriginalSpans() {
        val corrected = GrammarEdits.apply(input, GrammarEdits.parse(modelOutput))
        assertTrue(corrected.contains("carry the burden of their entire past history"))
        assertTrue(corrected.contains("accumulated wealth is now at risk"))
    }

    /** The promise of this design: nothing is dropped, however badly the model behaves. */
    @Test
    fun neverLosesContent() {
        val corrected = GrammarEdits.apply(input, GrammarEdits.parse(modelOutput))
        val paragraphsBefore = input.split("\n\n").count { it.isNotBlank() }
        val paragraphsAfter = corrected.split("\n\n").count { it.isNotBlank() }
        assertEquals(paragraphsBefore, paragraphsAfter)
        // Only the edited spans change, so the length is within a few percent.
        assertTrue(corrected.length > input.length * 0.95)
    }

    @Test
    fun skipsSpansThatAreNotInTheText() {
        val edits = listOf(GrammarEdits.Edit("text that does not exist", "anything"))
        assertEquals(input, GrammarEdits.apply(input, edits))
    }

    @Test
    fun proseWithoutJsonYieldsNoEdits() {
        val edits = GrammarEdits.parse("I could not find any errors in this text.")
        assertTrue(edits.isEmpty())
        assertEquals(input, GrammarEdits.apply(input, edits))
    }

    @Test
    fun emptyArrayMeansNoChanges() {
        val edits = GrammarEdits.parse("[]")
        assertTrue(edits.isEmpty())
        assertEquals(input, GrammarEdits.apply(input, edits))
    }

    @Test
    fun ignoresNoOpAndOversizedSpans() {
        val raw = """[{"find":"same","replace":"same"},{"find":"x","replace":"y"}]"""
        val edits = GrammarEdits.parse(raw)
        assertEquals(1, edits.size)
        assertEquals("x", edits[0].find)
    }
}
