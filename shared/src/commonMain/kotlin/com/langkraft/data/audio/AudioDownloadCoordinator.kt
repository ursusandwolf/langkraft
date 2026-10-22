package com.langkraft.data.audio

/** Platform lease covers SQL state, HTTP, storage publication and compensation. */
interface AudioDownloadCoordinator {
    /** Null means another owner is active; a null protocol disables crash recovery. */
    suspend fun tryAcquire(contentId: String): AudioDownloadLease?
}

interface AudioDownloadLease {
    val recoveryProtocol: String?
    suspend fun release()
}

object UncoordinatedAudioDownloads : AudioDownloadCoordinator {
    override suspend fun tryAcquire(contentId: String): AudioDownloadLease = object : AudioDownloadLease {
        override val recoveryProtocol: String? = null
        override suspend fun release() = Unit
    }
}

internal fun audioDownloadMetadataKey(contentId: String) = "audio_download_lock:$contentId"
