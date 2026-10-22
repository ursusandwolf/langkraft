package com.langkraft.data.audio

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.io.ioDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class PendingAudioAsset(val location: String, val contentId: String, val recoveryProtocol: String)

/** Enumerates only published assets with a known, durable pending marker. */
interface PendingAudioStorage {
    suspend fun listPending(): List<PendingAudioAsset>
    /** Resolve atomically; false means the candidate was already resolved/removed. */
    suspend fun resolvePending(location: String, referenced: Boolean): Boolean
}

class PendingAudioRecovery(
    private val access: DatabaseAccess,
    private val coordinator: AudioDownloadCoordinator,
    private val storage: PendingAudioStorage
) {
    suspend fun recover(): Int {
        var resolved = 0
        for (asset in storage.listPending()) {
            val lease = withContext(NonCancellable) { coordinator.tryAcquire(asset.contentId) } ?: continue
            try {
                if (lease.recoveryProtocol != asset.recoveryProtocol) continue
                access.run(ioDispatcher) {
                    val referenced = appDatabaseQueries.selectContentReferencingAudio(asset.location).awaitAsOneOrNull() != null
                    // Keep the local DB mutex and content lease through the short
                    // IDB mutation; no SQLite transaction spans the second store.
                    if (storage.resolvePending(asset.location, referenced)) resolved++
                }
            } finally { withContext(NonCancellable) { lease.release() } }
        }
        return resolved
    }
}
