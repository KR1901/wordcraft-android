package com.wordcraft.android.ai

enum class WritingAction(val label: String, val instruction: String) {
    FIX_GRAMMAR("Fix grammar", "Correct grammar, spelling, punctuation, and clarity while preserving meaning."),
    PARAPHRASE("Paraphrase", "Rewrite using different wording while preserving meaning."),
    PROFESSIONAL("Professional", "Rewrite in a polished, professional tone."),
    FRIENDLY("Friendly", "Rewrite in a warm, friendly, natural tone."),
    CONCISE("Make concise", "Make shorter without losing important information."),
    EXPAND("Expand", "Expand with useful detail while preserving intent."),
    SIMPLIFY("Simplify", "Rewrite using clear, simple language."),
    CONFIDENT("Confident", "Rewrite with an assured, confident voice without sounding arrogant."),
    EMPATHETIC("Empathetic", "Rewrite with warmth and understanding, acknowledging how the reader may feel."),
    PERSUASIVE("Persuasive", "Rewrite so it is more convincing and compelling while staying honest."),
    CASUAL("Casual", "Rewrite in a relaxed, everyday conversational tone."),
    FORMAL("Formal", "Rewrite in a formal, businesslike register."),
    ENTHUSIASTIC("Enthusiastic", "Rewrite with upbeat, enthusiastic energy.");

    /** Grammar fixing must never change the length or drop content. */
    val allowsLengthTarget: Boolean get() = this != FIX_GRAMMAR
}

/** How long the rewrite should be, relative to the input. */
enum class LengthTarget(val label: String, val instruction: String) {
    SHORT("Shorter", "Aim for roughly half the length of the original."),
    MEDIUM("Medium", "Aim for roughly three quarters of the length of the original."),
    SAME("Same length", "Keep it about as long as the original."),
    LONG("Longer", "Aim for about 1.5x the length of the original, adding substance not padding.")
}

data class WritingResult(val text: String, val explanation: String? = null)

sealed interface AssistantState {
    data object NotConfigured : AssistantState
    data object Working : AssistantState
    data class Success(val result: WritingResult) : AssistantState
    data class Failure(val message: String) : AssistantState
}

/**
 * Provider credentials must never be hard-coded in the APK. A production deployment should
 * use an authenticated HTTPS backend/proxy, rate limiting, consent, and clear text retention.
 */
interface WritingAssistant {
    suspend fun rewrite(
        input: String,
        action: WritingAction,
        length: LengthTarget = LengthTarget.SAME
    ): WritingResult

    /** Free-form prompt, for the chat screen. */
    suspend fun ask(prompt: String): WritingResult
}

class UnconfiguredWritingAssistant : WritingAssistant {
    override suspend fun rewrite(
        input: String,
        action: WritingAction,
        length: LengthTarget
    ): WritingResult {
        throw IllegalStateException("AI is not configured yet. Add a secure AI provider endpoint in Settings.")
    }

    override suspend fun ask(prompt: String): WritingResult {
        throw IllegalStateException("AI is not configured yet. Add a secure AI provider endpoint in Settings.")
    }
}
