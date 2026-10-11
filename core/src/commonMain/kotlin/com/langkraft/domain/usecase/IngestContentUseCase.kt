package com.langkraft.domain.usecase

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.ContentRepository
import kotlinx.coroutines.CancellationException

/**
 * Orchestrates the process of taking a YouTube URL, 
 * fetching audio/subs from backend, and saving to local DB.
 */
class IngestContentUseCase(
    private val contentRepository: ContentRepository
) {
    suspend operator fun invoke(youtubeUrl: String): Result<ImmersionContent> = try {
        require(youtubeUrl.isNotBlank()) { "Enter a YouTube URL" }
        // 1. Fetch metadata and links from Backend
        val remoteContent = contentRepository.fetchFromYouTube(youtubeUrl)
        
        // 2. Save initial structure to Local DB
        contentRepository.saveContent(remoteContent)
        
        // Complete the download before reporting an offline-ready import.
        val localPath = contentRepository.downloadAudio(remoteContent)
        
        // 4. Update content with local path
        val updatedContent = remoteContent.copy(localAudioPath = localPath)
        contentRepository.saveContent(updatedContent)
        
        Result.success(updatedContent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
