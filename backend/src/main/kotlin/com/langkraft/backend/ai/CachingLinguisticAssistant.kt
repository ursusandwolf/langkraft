package com.langkraft.backend.ai

import com.langkraft.domain.ai.*
import java.util.concurrent.ConcurrentHashMap

class CachingLinguisticAssistant(
    private val delegate: LinguisticAssistant
) : LinguisticAssistant {

    private val wordCache = ConcurrentHashMap<String, TranslationResult>()
    private val sentenceCache = ConcurrentHashMap<String, String>()
    private val analysisCache = ConcurrentHashMap<String, DeepAnalysisResult>()
    private val correctionCache = ConcurrentHashMap<String, CorrectionResult>()

    override suspend fun translateWord(word: String, context: String): TranslationResult {
        val key = "$word|$context"
        return wordCache.getOrPut(key) { delegate.translateWord(word, context) }
    }

    override suspend fun translateSentence(text: String): String {
        return sentenceCache.getOrPut(text) { delegate.translateSentence(text) }
    }

    override suspend fun analyzeSentence(text: String): DeepAnalysisResult {
        return analysisCache.getOrPut(text) { delegate.analyzeSentence(text) }
    }

    override suspend fun correctText(text: String): CorrectionResult {
        return correctionCache.getOrPut(text) { delegate.correctText(text) }
    }
}
