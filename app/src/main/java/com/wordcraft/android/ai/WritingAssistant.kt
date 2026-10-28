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
    ENTHUSIASTIC("Enthusiastic", "Rewrite with upbeat, enthusiastic energy.")
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
    suspend fun rewrite(input: String, action: WritingAction): WritingResult
}
class UnconfiguredWritingAssistant : WritingAssistant {
    override suspend fun rewrite(input: String, action: WritingAction): WritingResult {
        throw IllegalStateException("AI is not configured yet. Add a secure AI provider endpoint in Settings.")
    }
}
