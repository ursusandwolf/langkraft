package com.langkraft.ui.srs

import com.langkraft.domain.model.*
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.srs.SpacedRepetitionAlgorithm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SrsTrainingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeVocabularyRepository : VocabularyRepository {
        val words = MutableStateFlow<List<VocabularyWord>>(emptyList())
        val savedWords = mutableListOf<VocabularyWord>()

        override fun getWordsToReview(): Flow<List<VocabularyWord>> = words
        override fun getReviewCount(): Flow<Int> = flowOf(words.value.size)
        override suspend fun saveWord(word: VocabularyWord) {
            savedWords.add(word)
        }
        override suspend fun deleteWord(id: String) {}
        override suspend fun sync(lastSyncTimestamp: Long): Long = 0
        override fun getWordCountsByStatus(): Flow<Map<WordStatus, Long>> = flowOf(emptyMap())
        override fun getWordsAddedSince(timestamp: Long): Flow<Long> = flowOf(0)
        override suspend fun getSyncMetadata(key: String): String? = null
        override suspend fun setSyncMetadata(key: String, value: String) {}
    }

    private class FakeSrsAlgorithm : SpacedRepetitionAlgorithm {
        override fun calculateNextReview(word: VocabularyWord, quality: ReviewQuality): VocabularyWord {
            return word.copy(intervalDays = if (quality.sm2Value >= 3) 1 else 0)
        }
    }

    @Test
    fun testInitialLoading() = runTest {
        val repo = FakeVocabularyRepository()
        val word = VocabularyWord(id = "1", word = "Haus", contextSentence = "Das Haus ist gross")
        repo.words.value = listOf(word)

        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        
        assertEquals(word, viewModel.currentState.currentWord)
        assertEquals(1, viewModel.currentState.remainingCount)
        assertFalse(viewModel.currentState.isAnswerVisible)
    }

    @Test
    fun testSessionFlowWithReLearning() = runTest {
        val repo = FakeVocabularyRepository()
        val word1 = VocabularyWord(id = "1", word = "Haus", contextSentence = "Haus")
        val word2 = VocabularyWord(id = "2", word = "Auto", contextSentence = "Auto")
        repo.words.value = listOf(word1, word2)

        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        
        // 1. Current word is word1
        assertEquals(word1, viewModel.currentState.currentWord)
        
        // 2. Answer GOOD -> move to word2
        viewModel.onEvent(SrsEvent.SubmitResult(ReviewQuality.GOOD))
        assertEquals(word2, viewModel.currentState.currentWord)
        assertEquals(1, viewModel.currentState.remainingCount)
        assertEquals(1, repo.savedWords.size)
        
        // 3. Answer AGAIN (Failure) on word2 -> word2 stays in queue (moves to end)
        viewModel.onEvent(SrsEvent.SubmitResult(ReviewQuality.AGAIN))
        assertEquals(word2, viewModel.currentState.currentWord) // Only word2 left, so it's still current
        assertEquals(1, viewModel.currentState.remainingCount)
        
        // 4. Answer GOOD on word2 -> session finished
        viewModel.onEvent(SrsEvent.SubmitResult(ReviewQuality.GOOD))
        assertTrue(viewModel.currentState.isFinished)
        assertEquals(0, viewModel.currentState.remainingCount)
        assertEquals(3, repo.savedWords.size) // word1(good), word2(again), word2(good)
    }

    @Test
    fun testShowAnswer() = runTest {
        val repo = FakeVocabularyRepository()
        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        
        viewModel.onEvent(SrsEvent.ShowAnswer)
        assertTrue(viewModel.currentState.isAnswerVisible)
    }
}
