package com.langkraft.domain.ai

interface LinguisticAssistant {
    /**
     * Translates a word given its context sentence.
     */
    suspend fun translateWithContext(word: String, context: String): String
}

/**
 * Mock implementation for testing/MVP without API keys.
 */
class MockLinguisticAssistant : LinguisticAssistant {
    override suspend fun translateWithContext(word: String, context: String): String {
        return "Translation of '$word'"
    }
}
