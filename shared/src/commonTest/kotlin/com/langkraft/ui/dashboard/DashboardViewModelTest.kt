package com.langkraft.ui.dashboard

import com.langkraft.data.sync.ISyncManager
import com.langkraft.domain.repository.ImmersionStats
import com.langkraft.domain.model.*
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardViewModelTest {

    @Test
    fun test_should_update_state_when_SyncManager_reports_error() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        val fakeSyncManager = object : ISyncManager {
            override val isSyncing = MutableStateFlow(false)
            override val syncError = MutableStateFlow<String?>(null)
            override suspend fun sync(force: Boolean) {}
            
            fun setError(error: String?) { syncError.value = error }
        }
        
        val fakeRepo = object : VocabularyRepository {
            override fun getWordsToReview(): Flow<List<VocabularyWord>> = emptyFlow()
            override fun getReviewCount(): Flow<Int> = flowOf(0)
            override suspend fun saveWord(word: VocabularyWord) {}
            override suspend fun deleteWord(id: String) {}
            override suspend fun sync(lastSyncTimestamp: Long): Long = 0L
            override fun getWordCountsByStatus(): Flow<Map<WordStatus, Long>> = flowOf(emptyMap())
            override fun getWordsAddedSince(timestamp: Long): Flow<Long> = flowOf(0L)
            override suspend fun getSyncMetadata(key: String): String? = null
            override suspend fun setSyncMetadata(key: String, value: String) {}
        }
        
        val fakeContentRepo = object : LocalContentRepository {
            override fun getAllContent(): Flow<List<ImmersionContent>> = emptyFlow()
            override suspend fun getContentById(id: String): ImmersionContent? = null
            override suspend fun saveContent(content: ImmersionContent) {}
            override fun getImmersionStats(): Flow<ImmersionStats> = flowOf(ImmersionStats(0, 0))
        }

        val vm = DashboardViewModel(fakeContentRepo, fakeRepo, fakeSyncManager, testDispatcher)

        // Act
        fakeSyncManager.setError("Connection error")
        
        // Assert
        assertEquals("Connection error", vm.state.value.syncError)
    }
}
