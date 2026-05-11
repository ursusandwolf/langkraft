package com.langkraft.domain.usecase

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.ContentRepository

/**
 * Orchestrates the process of taking a YouTube URL, 
 * fetching audio/subs from backend, and saving to local DB.
 */
class IngestContentUseCase(
    private val contentRepository: ContentRepository
) {
    suspend operator fun invoke(youtubeUrl: String): Result<ImmersionContent> = runCatching {
        // 1. Fetch metadata and links from Backend
        val remoteContent = contentRepository.fetchFromYouTube(youtubeUrl)
        
        // 2. Save initial structure to Local DB (Status: IDLE)
        contentRepository.saveContent(remoteContent)
        
        // 3. We return here. The UI (ViewModel) will decide when to trigger downloadAudio.
        // This prevents blocking the whole ingestion flow on a large file download.
        remoteContent
    }
}
