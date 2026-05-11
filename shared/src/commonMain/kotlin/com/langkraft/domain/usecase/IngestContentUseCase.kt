package com.langkraft.domain.usecase

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.RemoteContentSource

/**
 * Orchestrates the process of taking a YouTube URL, 
 * fetching audio/subs from backend, and saving to local DB.
 */
class IngestContentUseCase(
    private val remoteSource: RemoteContentSource,
    private val localRepository: LocalContentRepository
) {
    suspend operator fun invoke(youtubeUrl: String): Result<ImmersionContent> = runCatching {
        // 1. Fetch metadata and links from Backend
        val remoteContent = remoteSource.fetchFromYouTube(youtubeUrl)
        
        // 2. Save initial structure to Local DB (Status: IDLE)
        localRepository.saveContent(remoteContent)
        
        // 3. We return here. The UI (ViewModel) will decide when to trigger downloadAudio.
        // This prevents blocking the whole ingestion flow on a large file download.
        remoteContent
    }
}
