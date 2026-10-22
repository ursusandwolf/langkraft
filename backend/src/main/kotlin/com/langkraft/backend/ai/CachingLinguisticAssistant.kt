package com.langkraft.backend.ai

import com.langkraft.domain.ai.*
import java.util.Collections
import java.util.LinkedHashMap

class CachingLinguisticAssistant(
    private val delegate: LinguisticAssistant,
    private val maxCacheSize: Int = 1000
) : LinguisticAssistant {

    private fun <K, V> createLruCache(maxSize: Int): MutableMap<K, V> {
        return Collections.synchronizedMap(object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<K, V>?): Boolean {
                return size > maxSize
            }
        })
    }

    private val wordCache = createLruCache<String, TranslationResult>(maxCacheSize)
    private val sentenceCache = createLruCache<String, String>(maxCacheSize)
    private val analysisCache = createLruCache<String, DeepAnalysisResult>(maxCacheSize)
    private val correctionCache = createLruCache<String, CorrectionResult>(maxCacheSize)

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

    private inline fun <K, V> MutableMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V {
        val value = get(key)
        return if (value == null) {
            val answer = defaultValue()
            put(key, answer)
            answer
        } else {
            value
        }
    }
}
