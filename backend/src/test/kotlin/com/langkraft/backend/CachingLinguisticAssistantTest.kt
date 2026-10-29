package com.langkraft.backend

import com.langkraft.backend.ai.CachingLinguisticAssistant
import com.langkraft.domain.ai.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CachingLinguisticAssistantTest {
    @Test fun wordAndContextCannotCollideAtDelimiter() = runTest {
        var calls = 0
        val assistant = CachingLinguisticAssistant(object : LinguisticAssistant {
            override suspend fun translateWord(word: String, context: String): TranslationResult {
                calls++
                return TranslationResult(context, word, "noun")
            }
            override suspend fun translateSentence(text: String): String = error("Unused")
            override suspend fun analyzeSentence(text: String): DeepAnalysisResult = error("Unused")
            override suspend fun correctText(text: String): CorrectionResult = error("Unused")
        })
        assertEquals("c", assistant.translateWord("a|b", "c").translation)
        assertEquals("b|c", assistant.translateWord("a", "b|c").translation)
        assertEquals("c", assistant.translateWord("a|b", "c").translation)
        assertEquals(2, calls)
    }
}
