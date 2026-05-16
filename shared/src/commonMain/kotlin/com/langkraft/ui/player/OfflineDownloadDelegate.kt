package com.langkraft.ui.player

import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.repository.AudioDownloader
import com.langkraft.domain.repository.LocalContentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OfflineDownloadDelegate(
    private val audioDownloader: AudioDownloader,
    private val contentRepository: LocalContentRepository,
    private val scope: CoroutineScope,
    private val updateState: ((PlayerState) -> PlayerState) -> Unit,
    private val getState: () -> PlayerState
) {

    fun handleToggleOffline() {
        val currentContent = getState().content ?: return
        if (currentContent.downloadStatus == DownloadStatus.DOWNLOADING) return

        scope.launch {
            try {
                audioDownloader.downloadAudio(currentContent)
                val updated = contentRepository.getContentById(currentContent.id)
                if (updated != null) {
                    updateState { it.copy(content = updated) }
                }
            } catch (e: Exception) {
                updateState { it.copy(error = "Download failed: ${e.message}") }
            }
        }
    }
}
