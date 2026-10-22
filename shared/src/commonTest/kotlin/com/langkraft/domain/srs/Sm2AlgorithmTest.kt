package com.langkraft.domain.srs

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.ReviewQuality
import com.langkraft.domain.model.WordStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Sm2AlgorithmTest {
    private val algorithm = Sm2Algorithm()
    private val initialWord = VocabularyWord(
        id = "1",
        word = "test",
        contextSentence = "test context",
        intervalDays = 0,
        easeFactor = 2.5,
        status = WordStatus.NEW
    )

    @Test
    fun testFirstCorrectReview() {
        val next = algorithm.calculateNextReview(initialWord, ReviewQuality.GOOD)
        assertEquals(1, next.intervalDays)
        assertEquals(WordStatus.LEARNING, next.status)
        assertTrue(next.nextReviewMs > 0)
    }

    @Test
    fun testSecondCorrectReview() {
        val wordAfterFirst = initialWord.copy(intervalDays = 1)
        val next = algorithm.calculateNextReview(wordAfterFirst, ReviewQuality.GOOD)
        assertEquals(6, next.intervalDays)
    }

    @Test
    fun testIntervalRounding() {
        // 5 * 1.3 = 6.5 -> should be 7 with roundToInt()
        val word = initialWord.copy(intervalDays = 5, easeFactor = 1.3)
        val next = algorithm.calculateNextReview(word, ReviewQuality.GOOD)
        assertEquals(7, next.intervalDays)
    }

    @Test
    fun testFailureResetsInterval() {
        val word = initialWord.copy(intervalDays = 10, status = WordStatus.LEARNING)
        val next = algorithm.calculateNextReview(word, ReviewQuality.AGAIN)
        assertEquals(0, next.intervalDays)
        assertEquals(1, next.lapseCount)
        assertEquals(WordStatus.LEARNING, next.status)
    }

    @Test
    fun testEasyReviewIncreasesEaseFactor() {
        val next = algorithm.calculateNextReview(initialWord, ReviewQuality.EASY)
        assertTrue(next.easeFactor > 2.5)
    }
}
