package com.langkraft.data.sync

import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class SyncManager(
    private val vocabularyRepository: VocabularyRepository
) {
    private val mutex = Mutex()
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        const val MIN_SYNC_INTERVAL_MS = 60_000L // 1 minute
    }

    private var lastAttemptTimestamp: Long = 0

    suspend fun sync(force: Boolean = false) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (!force && now - lastAttemptTimestamp < MIN_SYNC_INTERVAL_MS) return
        
        if (_isSyncing.value) return
        
        mutex.withLock {
            if (_isSyncing.value) return
            _isSyncing.value = true
        }

        lastAttemptTimestamp = now

        try {
            val lastSyncTimestamp = vocabularyRepository.getSyncMetadata(KEY_LAST_SYNC)?.toLongOrNull() ?: 0L
            val newTimestamp = vocabularyRepository.sync(lastSyncTimestamp)
            if (newTimestamp > lastSyncTimestamp) {
                vocabularyRepository.setSyncMetadata(KEY_LAST_SYNC, newTimestamp.toString())
            }
        } catch (e: Exception) {
            println("SyncManager: Sync failed: ${e.message}")
        } finally {
            _isSyncing.value = false
        }
    }
}
