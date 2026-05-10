package com.langkraft.domain.srs

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import kotlinx.datetime.Clock
import kotlin.math.max

/**
 * A simple SuperMemo-2 inspired algorithm for Spaced Repetition.
 */
object SrsEngine {
    
    fun calculateNextReview(word: VocabularyWord, quality: Int): VocabularyWord {
        val now = Clock.System.now().toEpochMilliseconds()
        
        // Quality: 0-5 (0: total blackout, 5: perfect response)
        if (quality < 3) {
            // Reset interval on failure
            return word.copy(
                intervalDays = 0,
                nextReviewMs = now, // Review again soon
                status = WordStatus.LEARNING
            )
        }

        val newEaseFactor = max(1.3, word.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)))
        
        val newInterval = when (word.intervalDays) {
            0 -> 1
            1 -> 6
            else -> (word.intervalDays * newEaseFactor).toInt()
        }

        val nextReviewMs = now + (newInterval * 24 * 60 * 60 * 1000L)
        
        return word.copy(
            intervalDays = newInterval,
            nextReviewMs = nextReviewMs,
            easeFactor = newEaseFactor,
            status = if (newInterval > 30) WordStatus.MASTERED else WordStatus.LEARNING
        )
    }
}
