package com.langkraft.ui.player

import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.AudioDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import com.langkraft.ui.BaseViewModel

import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.io.FileSystem
import com.langkraft.audio.AudioPlayer

class PlayerViewModel(
    private val contentRepository: LocalContentRepository,
    private val audioDownloader: AudioDownloader,
    private val vocabularyRepository: VocabularyRepository,
    linguisticAssistant: LinguisticAssistant,
    private val audioPlayer: AudioPlayer,
    private val fileSystem: FileSystem
) : BaseViewModel() {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val linguisticDelegate = PlayerLinguisticDelegate(linguisticAssistant, scope, _state)
    private val offlineDelegate = OfflineDownloadDelegate(audioDownloader, contentRepository, scope, _state)

    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.LoadContent -> loadContent(event.contentId)
            is PlayerEvent.PlayPause -> {
                if (_state.value.isPlaying) audioPlayer.pause() else audioPlayer.play()
                _state.update { it.copy(isPlaying = !it.isPlaying) }
            }
            is PlayerEvent.ToggleLoop -> {
                _state.update { it.copy(isLooping = !it.isLooping) }
            }
            is PlayerEvent.ToggleOffline -> {
                offlineDelegate.handleToggleOffline()
            }
            is PlayerEvent.SetPlaybackSpeed -> {
                _state.update { it.copy(playbackSpeed = event.speed) }
            }
            is PlayerEvent.SeekTo -> {
                _state.update { it.copy(currentTimeMs = event.timeMs) }
            }
            is PlayerEvent.WordClicked -> {
                linguisticDelegate.handleWordClicked(event.word, event.line)
            }
            is PlayerEvent.DeepAnalysisClicked -> {
                linguisticDelegate.handleDeepAnalysis(event.line)
            }
            is PlayerEvent.MemorizationClicked -> {
                _state.update { it.copy(memorizationText = event.text) }
            }
            is PlayerEvent.ToggleTranslation -> {
                linguisticDelegate.handleToggleTranslation(event.line)
            }
            is PlayerEvent.ToggleLemmatization -> {
                linguisticDelegate.handleToggleLemmatization(event.line)
            }
            is PlayerEvent.DismissWordDetails -> {
                _state.update { it.copy(selectedWord = null, wordTranslation = null) }
            }
            is PlayerEvent.DismissDeepAnalysis -> {
                _state.update { it.copy(deepAnalysis = null) }
            }
            is PlayerEvent.DismissMemorization -> {
                _state.update { it.copy(memorizationText = null) }
            }
        }
    }

    fun saveWord(word: VocabularyWord) {
        scope.launch {
            vocabularyRepository.saveWord(word)
            _state.update { it.copy(selectedWord = null, wordTranslation = null) }
        }
    }

    private fun loadContent(id: String) {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            val content = contentRepository.getContentById(id)
            if (content == null) {
                _state.update { it.copy(isLoading = false, error = "Content not found") }
                return@launch
            }
            _state.update { it.copy(isLoading = false, content = content) }
            
            audioPlayer.load(content.getPlaybackUrl())
        }
    }
    
    // This will be called by the actual audio player implementation
    fun updateTime(timeMs: Long) {
        val currentState = _state.value
        if (currentState.isLooping) {
            val currentLine = currentState.content?.subtitles?.find { 
                currentState.currentTimeMs in it.startMs..it.endMs 
            }
            if (currentLine != null && timeMs > currentLine.endMs) {
                // Seek back to start of current line
                _state.update { it.copy(currentTimeMs = currentLine.startMs) }
                return
            }
        }
        _state.update { it.copy(currentTimeMs = timeMs) }
    }
}
