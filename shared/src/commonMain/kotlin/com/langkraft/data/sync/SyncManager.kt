package com.langkraft.data.sync

import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

interface ISyncManager {
    val isSyncing: StateFlow<Boolean>
    val syncError: StateFlow<String?>
    suspend fun sync(force: Boolean = false)
}

class SyncManager(
    private val vocabularyRepository: VocabularyRepository
) : ISyncManager {
    private val mutex = Mutex()
    
    private val _isSyncing = MutableStateFlow(false)
    override val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _syncError = MutableStateFlow<String?>(null)
    override val syncError: StateFlow<String?> = _syncError

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        const val MIN_SYNC_INTERVAL_MS = 60_000L // 1 minute
    }

    private var lastAttemptTimestamp: Long = 0

    override suspend fun sync(force: Boolean) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (!force && now - lastAttemptTimestamp < MIN_SYNC_INTERVAL_MS) return
        
        if (mutex.isLocked) return

        mutex.withLock {
            _isSyncing.value = true
            _syncError.value = null
            lastAttemptTimestamp = now

            try {
                val lastSyncTimestamp = vocabularyRepository.getSyncMetadata(KEY_LAST_SYNC)?.toLongOrNull() ?: 0L
                val newTimestamp = vocabularyRepository.sync(lastSyncTimestamp)
                if (newTimestamp > lastSyncTimestamp) {
                    vocabularyRepository.setSyncMetadata(KEY_LAST_SYNC, newTimestamp.toString())
                }
            } catch (e: Exception) {
                _syncError.value = e.message ?: "Unknown sync error"
            } finally {
                _isSyncing.value = false
            }
        }
    }
}
