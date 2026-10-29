package com.langkraft.backend.ai

import com.langkraft.domain.ai.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CachingLinguisticAssistant(
    private val delegate: LinguisticAssistant,
    maxCacheSize: Int = 1000,
    maxPayloadBytesPerTask: Long = 8 * 1024 * 1024,
    ttlMs: Long = 600_000
) : LinguisticAssistant {
    override suspend fun capabilities(): AiCapabilities = delegate.capabilities()

    // This instance belongs to one immutable provider/model/prompt configuration.
    private val wordCache = AiResultCache<Pair<String, String>, TranslationResult>(maxCacheSize, maxPayloadBytesPerTask, ttlMs,
        { key, value -> 2L * (key.first.length + key.second.length + Json.encodeToString(value).length) })
    private val sentenceCache = AiResultCache<String, String>(maxCacheSize, maxPayloadBytesPerTask, ttlMs,
        { key, value -> 2L * (key.length + value.length) })
    private val analysisCache = AiResultCache<String, DeepAnalysisResult>(maxCacheSize, maxPayloadBytesPerTask, ttlMs,
        { key, value -> 2L * (key.length + Json.encodeToString(value).length) })
    private val correctionCache = AiResultCache<String, CorrectionResult>(maxCacheSize, maxPayloadBytesPerTask, ttlMs,
        { key, value -> 2L * (key.length + Json.encodeToString(value).length) })

    override suspend fun translateWord(word: String, context: String): TranslationResult {
        val key = word to context
        return wordCache.getOrLoad(key) { delegate.translateWord(word, context) }
    }

    override suspend fun translateSentence(text: String): String {
        return sentenceCache.getOrLoad(text) { delegate.translateSentence(text) }
    }

    override suspend fun analyzeSentence(text: String): DeepAnalysisResult {
        return analysisCache.getOrLoad(text) { delegate.analyzeSentence(text) }
    }

    override suspend fun correctText(text: String): CorrectionResult {
        return correctionCache.getOrLoad(text) { delegate.correctText(text) }
    }

}
