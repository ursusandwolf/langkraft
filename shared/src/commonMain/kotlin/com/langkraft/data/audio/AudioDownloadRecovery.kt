package com.langkraft.data.audio

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.io.ioDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Recover only protocol-marked rows while holding the same lease as the downloader. */
class AudioDownloadRecovery(
    private val access: DatabaseAccess,
    private val coordinator: AudioDownloadCoordinator
) {
    suspend fun recover(): Int {
        val candidates = access.run(ioDispatcher) {
            appDatabaseQueries.selectAllContent().awaitAsList()
                .filter { it.downloadStatus == DownloadStatus.DOWNLOADING.name }
        }
        var recovered = 0
        for (candidate in candidates) {
            val lease = withContext(NonCancellable) { coordinator.tryAcquire(candidate.id) } ?: continue
            try {
                val protocol = lease.recoveryProtocol ?: continue
                access.run(ioDispatcher) {
                    transaction {
                        val row = appDatabaseQueries.selectContentById(candidate.id).awaitAsOneOrNull()
                        val key = audioDownloadMetadataKey(candidate.id)
                        val marker = appDatabaseQueries.getSyncMetadata(key).awaitAsOneOrNull()
                        if (row?.downloadStatus == DownloadStatus.DOWNLOADING.name && marker == protocol) {
                            val status = if (row.localAudioPath != null) DownloadStatus.COMPLETED else DownloadStatus.ERROR
                            appDatabaseQueries.updateDownloadStatus(status.name, row.localAudioPath, row.id)
                            appDatabaseQueries.deleteSyncMetadata(key)
                            recovered++
                        }
                    }
                }
            } finally { withContext(NonCancellable) { lease.release() } }
        }
        return recovered
    }
}
