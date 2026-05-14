package com.langkraft.data.sync

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

open class FakeVocabularyRepository : VocabularyRepository {
    var syncCallCount = 0
    var lastSyncTimestampProvided: Long = -1
    var nextSyncResult: Long = 0
    var syncResultOverride: Long? = null
    
    private val metadata = mutableMapOf<String, String>()

    override fun getWordsToReview(): Flow<List<VocabularyWord>> = emptyFlow()
    override fun getReviewCount(): Flow<Int> = emptyFlow()
    override suspend fun saveWord(word: VocabularyWord) {}
    override suspend fun deleteWord(id: String) {}

    override suspend fun sync(lastSyncTimestamp: Long): Long {
        syncCallCount++
        lastSyncTimestampProvided = lastSyncTimestamp
        return syncResultOverride ?: nextSyncResult
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
    fun test_sync_success_and_persistence() = runTest {
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
    fun test_sync_throttling() = runTest {
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
    fun test_sync_uses_persisted_timestamp() = runTest {
        val repository = FakeVocabularyRepository()
        repository.setSyncMetadata("last_sync_timestamp", "999")
        val syncManager = SyncManager(repository)
        
        repository.nextSyncResult = 2000L
        
        syncManager.sync()
        
        assertEquals(999L, repository.lastSyncTimestampProvided)
        assertEquals("2000", repository.getSyncMetadata("last_sync_timestamp"))
    }

    @Test
    fun test_sync_error_state_handling() = runTest {
        var shouldThrow = true
        val repository = object : FakeVocabularyRepository() {
            override suspend fun sync(lastSyncTimestamp: Long): Long {
                if (shouldThrow) throw Exception("Network error")
                return 5000L
            }
        }
        val syncManager = SyncManager(repository)
        
        // 1. Trigger error
        syncManager.sync(force = true)
        assertEquals("Network error", syncManager.syncError.value)
        
        // 2. Clear error on success
        shouldThrow = false
        syncManager.sync(force = true)
        assertNull(syncManager.syncError.value)
    }

    @Test
    fun test_sync_concurrent_calls_are_throttled() = runTest {
        val repository = object : FakeVocabularyRepository() {
            var isRunning = false
            override suspend fun sync(lastSyncTimestamp: Long): Long {
                if (isRunning) throw IllegalStateException("Concurrent sync detected")
                isRunning = true
                kotlinx.coroutines.delay(100) 
                isRunning = false
                syncCallCount++
                return 1000L
            }
        }
        val syncManager = SyncManager(repository)
        
        // Launch two syncs concurrently
        val job1 = launch { syncManager.sync(force = true) }
        val job2 = launch { syncManager.sync(force = true) }
        
        joinAll(job1, job2)
        
        assertEquals(1, repository.syncCallCount, "Sync should only be called once when concurrent")
    }

    @Test
    fun test_sync_handles_null_metadata_gracefully() = runTest {
        val repository = FakeVocabularyRepository()
        val syncManager = SyncManager(repository)
        
        repository.nextSyncResult = 500L
        syncManager.sync(force = true)
        
        assertEquals(0L, repository.lastSyncTimestampProvided, "Should sync from 0L if metadata is null")
    }

    @Test
    fun test_sync_handles_invalid_metadata_gracefully() = runTest {
        val repository = FakeVocabularyRepository()
        repository.setSyncMetadata("last_sync_timestamp", "not-a-number")
        val syncManager = SyncManager(repository)
        
        repository.nextSyncResult = 500L
        syncManager.sync(force = true)
        
        assertEquals(0L, repository.lastSyncTimestampProvided, "Should sync from 0L if metadata is invalid")
    }
}
