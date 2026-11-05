package com.langkraft.ui.dashboard

import com.langkraft.data.sync.ISyncManager
import com.langkraft.domain.repository.ImmersionStats
import com.langkraft.domain.model.*
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private fun fakeSync() = object : ISyncManager {
        override val isSyncing = MutableStateFlow(false)
        override val syncError = MutableStateFlow<String?>(null)
        override suspend fun sync(force: Boolean) {}
    }

    private fun fakeVocab() = object : VocabularyRepository {
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

    private fun fakeContent() = object : LocalContentRepository {
        override fun getAllContent(): Flow<List<ImmersionContent>> = emptyFlow()
        override suspend fun getContentById(id: String): ImmersionContent? = null
        override suspend fun saveContent(content: ImmersionContent) {}
        override fun getImmersionStats(): Flow<ImmersionStats> = flowOf(ImmersionStats(0, 0))
    }

    @Test
    fun guestDoesNotSurfaceSyncErrors() = runTest {
        val dispatcher = UnconfinedTestDispatcher()
        val sync = fakeSync()
        val vm = DashboardViewModel(fakeContent(), fakeVocab(), sync, MutableStateFlow(null), dispatcher)
        sync.syncError.value = "Please sign in through Account to sync (HTTP 401)."
        assertNull(vm.state.value.syncError)
    }

    @Test
    fun signedInSurfacesSyncErrors() = runTest {
        val dispatcher = UnconfinedTestDispatcher()
        val sync = fakeSync()
        val vm = DashboardViewModel(
            fakeContent(), fakeVocab(), sync, MutableStateFlow("token"), dispatcher
        )
        sync.syncError.value = "Connection error"
        assertEquals("Connection error", vm.state.value.syncError)
    }
}
