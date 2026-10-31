package com.wordcraft.android.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the rewrite guards. Each case is a failure that actually shipped:
 * a near-copy sold as a rewrite, a one-word answer, a repeated paragraph, and a summary
 * passed off as "same length".
 */
class RewriteQualityTest {

    private val input = """
        Countries carry baggage of their entire past history.

        If we are expecting our hard work, sincerity and talent will stop them from attacking NRIs,
        we are totally mistaken. Remember this is not just their personal wealth, it is Hindu wealth
        too. One must study the pages of history to know how brutally they behaved when they did not
        have the reserve currency status for pound and then for the dollar.
    """.trimIndent()

    @Test
    fun nearCopyWithTwoCommasCountsAsEcho() {
        // This is exactly what Qwen3.5 2B returned and the app showed as a successful paraphrase.
        val output = input
            .replace("carry baggage", "carry the baggage")
            .replace("sincerity and talent", "sincerity, and talent")
        assertTrue(RewriteQuality.isEcho(input, output))
    }

    @Test
    fun genuineParaphraseIsNotEcho() {
        val output = """
            Nations carry a heavy burden of their past history. Expecting our hard work and sincerity
            to stop attacks on NRIs would be a mistake. Their accumulated wealth now stands at risk,
            and that wealth is not only personal. History shows how they behaved without the reserve
            currency status for the pound and then the dollar.
        """.trimIndent()
        assertFalse(RewriteQuality.isEcho(input, output))
    }

    @Test
    fun oneWordAnswerIsAFragment() {
        assertTrue(RewriteQuality.isFragment(input, "and"))
    }

    @Test
    fun shortInputsAreNotTreatedAsFragments() {
        assertFalse(RewriteQuality.isFragment("hi there", "hello there"))
    }

    @Test
    fun repeatedParagraphIsDegenerate() {
        val sentence = "One must study the pages of history to know how brutally they behaved then."
        val output = listOf(sentence, sentence, sentence).joinToString(" ")
        assertTrue(RewriteQuality.isDegenerate(output))
    }

    @Test
    fun normalMultiSentenceTextIsNotDegenerate() {
        assertFalse(RewriteQuality.isDegenerate(input))
    }

    @Test
    fun halfLengthWithSameLengthRequestedIsCollapsed() {
        val output = "Countries carry baggage of their past. Hard work will not stop attacks on NRIs."
        assertTrue(RewriteQuality.isCollapsed(input, output, sameLengthRequested = true))
    }

    @Test
    fun shorterRequestsMayShorten() {
        val output = "Countries carry baggage of their past. Hard work will not stop attacks on NRIs."
        assertFalse(RewriteQuality.isCollapsed(input, output, sameLengthRequested = false))
    }
}
