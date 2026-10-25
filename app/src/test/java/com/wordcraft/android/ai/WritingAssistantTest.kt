package com.wordcraft.android.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WritingAssistantTest {
    @Test
    fun unconfiguredProviderFailsClearly() {
        val assistant = UnconfiguredWritingAssistant()
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { assistant.rewrite("hello", WritingAction.FIX_GRAMMAR) }
        }
        assertEquals("AI is not configured yet. Add a secure AI provider endpoint in Settings.", error.message)
    }

    @Test
    fun writingActionsHaveLabels() {
        assertEquals("Fix grammar", WritingAction.FIX_GRAMMAR.label)
        assertEquals("Paraphrase", WritingAction.PARAPHRASE.label)
        assertEquals(7, WritingAction.entries.size)
    }
}
