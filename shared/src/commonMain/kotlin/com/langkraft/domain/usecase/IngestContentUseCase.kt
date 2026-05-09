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
        
        // 2. Save initial structure to Local DB
        contentRepository.saveContent(remoteContent)
        
        // 3. Start background download of audio for offline access
        val localPath = contentRepository.downloadAudio(remoteContent)
        
        // 4. Update content with local path
        val updatedContent = remoteContent.copy(localAudioPath = localPath)
        contentRepository.saveContent(updatedContent)
        
        updatedContent
    }
}
