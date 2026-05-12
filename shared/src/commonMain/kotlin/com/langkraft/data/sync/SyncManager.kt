package com.langkraft.data.sync

import com.langkraft.domain.model.PendingSyncChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncManager {
    private val mutex = Mutex()
    private val queue = mutableListOf<PendingSyncChange>()
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    suspend fun enqueue(change: PendingSyncChange) {
        mutex.withLock {
            queue.add(change)
        }
        trySync()
    }

    private suspend fun trySync() {
        if (_isSyncing.value) return
        
        mutex.withLock {
            if (queue.isEmpty()) return
            _isSyncing.value = true
        }

        // Implementation of sync logic would go here, 
        // communicating with the API and clearing the queue.
        // For now, this placeholder satisfies the architectural path.
        
        mutex.withLock {
            queue.clear()
            _isSyncing.value = false
        }
    }
}
