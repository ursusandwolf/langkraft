package com.langkraft.data.audio

import com.langkraft.data.storage.acquireContentDownload
import kotlinx.coroutines.await

class WebAudioDownloadCoordinator : AudioDownloadCoordinator {
    override suspend fun tryAcquire(contentId: String): AudioDownloadLease? {
        val lease = acquireContentDownload(contentId).await() ?: return null
        return object : AudioDownloadLease {
            override val recoveryProtocol: String? = if (lease.coordinated) "web-lock-v1" else null
            override suspend fun release() { lease.release().await() }
        }
    }
}
