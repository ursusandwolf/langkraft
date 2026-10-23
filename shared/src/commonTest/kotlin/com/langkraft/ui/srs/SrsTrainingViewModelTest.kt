package com.langkraft.ui.srs

import com.langkraft.domain.model.*
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.srs.SpacedRepetitionAlgorithm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SrsTrainingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeVocabularyRepository : VocabularyRepository {
        val words = MutableStateFlow<List<VocabularyWord>>(emptyList())
        val savedWords = mutableListOf<VocabularyWord>()
        var saveAction: suspend () -> Unit = {}
        var loadError: Exception? = null
        var saveAttempts = 0

        override fun getWordsToReview(): Flow<List<VocabularyWord>> = flow {
            loadError?.let { throw it }
            emitAll(words)
        }
        override fun getReviewCount(): Flow<Int> = flowOf(words.value.size)
        override suspend fun saveWord(word: VocabularyWord) {
            saveAttempts++
            saveAction()
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
    fun keepsCardUntilSaveCompletesAndIgnoresRepeatedSubmit() = runTest {
        val repo = FakeVocabularyRepository()
        val word = VocabularyWord(id = "1", word = "Haus", contextSentence = "Haus")
        repo.words.value = listOf(word)
        val saving = CompletableDeferred<Unit>()
        repo.saveAction = { saving.await() }
        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        try {
            viewModel.onEvent(SrsEvent.ShowAnswer)
            viewModel.onEvent(SrsEvent.SubmitResult(ReviewQuality.GOOD))
            assertEquals(word, viewModel.currentState.currentWord)
            assertFalse(viewModel.currentState.isFinished)
            assertTrue(viewModel.currentState.isSaving)
            viewModel.onEvent(SrsEvent.SubmitResult(ReviewQuality.EASY))
            viewModel.onEvent(SrsEvent.Restart)
            viewModel.onEvent(SrsEvent.LoadQueue)
            assertEquals(1, repo.saveAttempts)
            saving.complete(Unit)
            assertTrue(viewModel.currentState.isFinished)
            assertEquals(1, repo.savedWords.size)
        } finally { saving.complete(Unit); viewModel.onCleared() }
    }

    @Test
    fun failedSaveKeepsAnswerAndCanBeRetried() = runTest {
        val repo = FakeVocabularyRepository()
        val word = VocabularyWord(id = "1", word = "Haus", contextSentence = "Haus")
        repo.words.value = listOf(word)
        repo.saveAction = { throw IllegalStateException("Disk full") }
        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        try {
            viewModel.onEvent(SrsEvent.ShowAnswer)
            viewModel.onEvent(SrsEvent.SubmitResult(ReviewQuality.GOOD))
            assertEquals(listOf(word), viewModel.currentState.queue)
            assertEquals(word, viewModel.currentState.currentWord)
            assertTrue(viewModel.currentState.isAnswerVisible)
            assertFalse(viewModel.currentState.isSaving)
            assertFalse(viewModel.currentState.isFinished)
            assertNotNull(viewModel.currentState.error)
            assertTrue(repo.savedWords.isEmpty())
            repo.saveAction = {}
            viewModel.onEvent(SrsEvent.SubmitResult(ReviewQuality.GOOD))
            assertEquals(1, repo.savedWords.size)
            assertTrue(viewModel.currentState.isFinished)
            assertNull(viewModel.currentState.error)
        } finally { viewModel.onCleared() }
    }

    @Test
    fun loadFailureIsNotAnEmptySessionAndCanBeRetried() = runTest {
        val repo = FakeVocabularyRepository()
        val word = VocabularyWord(id = "1", word = "Haus", contextSentence = "Haus")
        repo.words.value = listOf(word)
        repo.loadError = IllegalStateException("Storage unavailable")
        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        try {
            assertFalse(viewModel.currentState.isLoading)
            assertFalse(viewModel.currentState.isFinished)
            assertNotNull(viewModel.currentState.error)
            repo.loadError = null
            viewModel.onEvent(SrsEvent.LoadQueue)
            assertEquals(word, viewModel.currentState.currentWord)
            assertNull(viewModel.currentState.error)
        } finally { viewModel.onCleared() }
    }

    @Test
    fun cancellationDoesNotReportSuccessOrStorageFailure() = runTest {
        val repo = FakeVocabularyRepository()
        val word = VocabularyWord(id = "1", word = "Haus", contextSentence = "Haus")
        repo.words.value = listOf(word)
        repo.saveAction = { throw CancellationException("Review closed") }
        val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
        try {
            viewModel.onEvent(SrsEvent.SubmitResult(ReviewQuality.GOOD))
            assertEquals(word, viewModel.currentState.currentWord)
            assertFalse(viewModel.currentState.isFinished)
            assertFalse(viewModel.currentState.isSaving)
            assertNull(viewModel.currentState.error)
            assertTrue(repo.savedWords.isEmpty())
        } finally { viewModel.onCleared() }
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
