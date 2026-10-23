package com.langkraft.domain.usecase

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.RemoteContentSource
import kotlinx.coroutines.CancellationException

/**
 * Orchestrates the process of taking a YouTube URL, 
 * fetching audio/subs from backend, and saving to local DB.
 */
class IngestContentUseCase(
    private val remoteSource: RemoteContentSource,
    private val localRepository: LocalContentRepository
) {
    suspend operator fun invoke(youtubeUrl: String): Result<ImmersionContent> = try {
        // 1. Fetch metadata and links from Backend
        val remoteContent = remoteSource.fetchFromYouTube(youtubeUrl)
        
        // A server filesystem path/status cannot describe this client's offline copy.
        val imported = remoteContent.copy(localAudioPath = null, downloadStatus = DownloadStatus.IDLE)
        // The repository retains an existing completed client copy on re-import.
        localRepository.saveContent(imported)
        
        // 3. We return here. The UI (ViewModel) will decide when to trigger downloadAudio.
        // This prevents blocking the whole ingestion flow on a large file download.
        Result.success(imported)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }
}
