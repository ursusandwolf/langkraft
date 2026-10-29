package com.langkraft.domain.ai

import kotlinx.serialization.Serializable

interface LinguisticAssistant {
    suspend fun capabilities(): AiCapabilities = AiCapabilities(enabled = true)
    /**
     * Translates a word given its context sentence.
     */
    suspend fun translateWord(word: String, context: String): TranslationResult

    /**
     * Translates a whole sentence/subtitle line.
     */
    suspend fun translateSentence(text: String): String

    /**
     * Performs a deep grammatical analysis of a sentence.
     */
    suspend fun analyzeSentence(text: String): DeepAnalysisResult

    /**
     * Corrects a user-written text and provides explanations.
     */
    suspend fun correctText(text: String): CorrectionResult
}

@Serializable
data class AiCapabilities(val enabled: Boolean, val provider: String? = null)

@Serializable
data class TranslationResult(
    val translation: String,
    val lemma: String,
    val partOfSpeech: String,
    val explanation: String? = null
)

@Serializable
data class DeepAnalysisResult(
    val words: List<AnalyzedWord>,
    val syntaxExplanation: String
)

@Serializable
data class AnalyzedWord(
    val original: String,
    val lemma: String,
    val grammaticalInfo: String, // e.g., "Noun, feminine, Akkusativ"
    val roleInSentence: String // e.g., "Direct Object"
)

@Serializable
data class CorrectionResult(
    val originalText: String,
    val correctedText: String,
    val changes: List<TextChange>
)

@Serializable
data class TextChange(
    val original: String,
    val replacement: String,
    val explanation: String
)
