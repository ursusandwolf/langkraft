package com.langkraft.data.sync

import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncManager(
    private val vocabularyRepository: VocabularyRepository
) {
    private val mutex = Mutex()
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    // In a real app, this would be persisted (e.g., using DataStore or a Settings table)
    private var lastSyncTimestamp: Long = 0

    suspend fun sync() {
        if (_isSyncing.value) return
        
        mutex.withLock {
            if (_isSyncing.value) return
            _isSyncing.value = true
        }

        try {
            val newTimestamp = vocabularyRepository.sync(lastSyncTimestamp)
            lastSyncTimestamp = newTimestamp
        } catch (e: Exception) {
            println("SyncManager: Sync failed: ${e.message}")
        } finally {
            _isSyncing.value = false
        }
    }
}
