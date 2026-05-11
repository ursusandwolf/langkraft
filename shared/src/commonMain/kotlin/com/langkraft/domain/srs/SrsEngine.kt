package com.langkraft.domain.srs

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import kotlinx.datetime.Clock
import kotlin.math.max

/**
 * Interface for Spaced Repetition Algorithms.
 */
interface SpacedRepetitionAlgorithm {
    fun calculateNextReview(word: VocabularyWord, quality: Int): VocabularyWord
}

/**
 * SuperMemo-2 inspired algorithm implementation.
 * See: https://www.supermemo.com/en/blog/application-of-a-computer-to-improve-the-results-of-teaching
 */
class Sm2Algorithm : SpacedRepetitionAlgorithm {

    companion object {
        private const val MIN_EASE_FACTOR = 1.3
        private const val SUCCESS_THRESHOLD = 3
        private const val MASTERED_DAYS_THRESHOLD = 30
        private const val MS_IN_DAY = 24 * 60 * 60 * 1000L
        
        private const val SM2_MAX_QUALITY = 5
        private const val SM2_EF_BASE_MODIFIER = 0.1
        private const val SM2_EF_LINEAR_MODIFIER = 0.08
        private const val SM2_EF_QUADRATIC_MODIFIER = 0.02
    }
    
    override fun calculateNextReview(word: VocabularyWord, quality: Int): VocabularyWord {
        val now = Clock.System.now().toEpochMilliseconds()
        
        // Quality: 0-5 (0: total blackout, 5: perfect response)
        if (quality < SUCCESS_THRESHOLD) {
            // Reset interval on failure
            return word.copy(
                intervalDays = 0,
                nextReviewMs = now, // Review again soon
                status = WordStatus.LEARNING,
                lastUpdated = now
            )
        }

        val qDiff = SM2_MAX_QUALITY - quality
        val newEaseFactor = max(
            MIN_EASE_FACTOR, 
            word.easeFactor + (SM2_EF_BASE_MODIFIER - qDiff * (SM2_EF_LINEAR_MODIFIER + qDiff * SM2_EF_QUADRATIC_MODIFIER))
        )
        
        val newInterval = when (word.intervalDays) {
            0 -> 1
            1 -> 6
            else -> (word.intervalDays * newEaseFactor).toInt()
        }

        val nextReviewMs = now + (newInterval * MS_IN_DAY)
        
        return word.copy(
            intervalDays = newInterval,
            nextReviewMs = nextReviewMs,
            easeFactor = newEaseFactor,
            status = if (newInterval > MASTERED_DAYS_THRESHOLD) WordStatus.MASTERED else WordStatus.LEARNING,
            lastUpdated = now
        )
    }
}
