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
            return word.copy(intervalDays = 1)
        }
    }

    @Test
    fun testInitialLoading() = runTest {
        val repo = FakeVocabularyRepository()
        val word = VocabularyWord(id = "1", word = "Haus", contextSentence = "Das Haus ist gross")
        repo.words.value = listOf(word)

        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        
        // Wait for flow to emit
        assertEquals(word, viewModel.state.value.currentWord)
        assertEquals(1, viewModel.state.value.remainingCount)
        assertFalse(viewModel.state.value.isAnswerVisible)
    }

    @Test
    fun testShowAnswer() = runTest {
        val repo = FakeVocabularyRepository()
        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        
        viewModel.showAnswer()
        assertTrue(viewModel.state.value.isAnswerVisible)
    }

    @Test
    fun testSubmitResult() = runTest {
        val repo = FakeVocabularyRepository()
        val word = VocabularyWord(id = "1", word = "Haus", contextSentence = "Das Haus ist gross")
        repo.words.value = listOf(word)
        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)

        viewModel.submitResult(ReviewQuality.GOOD)
        
        assertEquals(1, repo.savedWords.size)
        assertEquals(1, repo.savedWords.first().intervalDays)
    }
}
