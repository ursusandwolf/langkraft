package com.langkraft.domain.ai

/**
 * Mock implementation for tests only.
 */
class MockLinguisticAssistant : LinguisticAssistant {
    override suspend fun translateWord(word: String, context: String): TranslationResult {
        return TranslationResult("Translation of '$word'", word, "Noun")
    }

    override suspend fun translateSentence(text: String): String {
        return "Translated: $text"
    }

    override suspend fun analyzeSentence(text: String): DeepAnalysisResult {
        return DeepAnalysisResult(emptyList(), "This is a mock analysis.")
    }

    override suspend fun correctText(text: String): CorrectionResult {
        return CorrectionResult(text, text, emptyList())
    }
}
