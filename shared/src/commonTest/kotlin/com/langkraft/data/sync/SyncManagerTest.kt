package com.langkraft.data.sync

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeVocabularyRepository : VocabularyRepository {
    var syncCallCount = 0
    var lastSyncTimestampProvided: Long = -1
    var nextSyncResult: Long = 0
    
    private val metadata = mutableMapOf<String, String>()

    override fun getWordsToReview(): Flow<List<VocabularyWord>> = emptyFlow()
    override fun getReviewCount(): Flow<Int> = emptyFlow()
    override suspend fun saveWord(word: VocabularyWord) {}
    override suspend fun deleteWord(id: String) {}

    override suspend fun sync(lastSyncTimestamp: Long): Long {
        syncCallCount++
        lastSyncTimestampProvided = lastSyncTimestamp
        return nextSyncResult
    }

    override fun getWordCountsByStatus(): Flow<Map<WordStatus, Long>> = emptyFlow()
    override fun getWordsAddedSince(timestamp: Long): Flow<Long> = emptyFlow()

    override suspend fun getSyncMetadata(key: String): String? = metadata[key]
    override suspend fun setSyncMetadata(key: String, value: String) {
        metadata[key] = value
    }
}

class SyncManagerTest {

    @Test
    fun `test sync - success and persistence`() = runTest {
        val repository = FakeVocabularyRepository()
        val syncManager = SyncManager(repository)
        
        repository.nextSyncResult = 12345L
        
        syncManager.sync()
        
        assertEquals(1, repository.syncCallCount)
        assertEquals(0L, repository.lastSyncTimestampProvided)
        assertEquals("12345", repository.getSyncMetadata("last_sync_timestamp"))
        assertFalse(syncManager.isSyncing.value)
    }

    @Test
    fun `test sync - throttling`() = runTest {
        val repository = FakeVocabularyRepository()
        val syncManager = SyncManager(repository)
        
        repository.nextSyncResult = 1000L
        
        // First sync
        syncManager.sync()
        assertEquals(1, repository.syncCallCount)
        
        // Second sync immediately after (should be throttled)
        syncManager.sync()
        assertEquals(1, repository.syncCallCount, "Should be throttled")
        
        // Force sync
        syncManager.sync(force = true)
        assertEquals(2, repository.syncCallCount, "Force sync should bypass throttling")
    }

    @Test
    fun `test sync - uses persisted timestamp`() = runTest {
        val repository = FakeVocabularyRepository()
        repository.setSyncMetadata("last_sync_timestamp", "999")
        val syncManager = SyncManager(repository)
        
        repository.nextSyncResult = 2000L
        
        syncManager.sync()
        
        assertEquals(999L, repository.lastSyncTimestampProvided)
        assertEquals("2000", repository.getSyncMetadata("last_sync_timestamp"))
    }
}
