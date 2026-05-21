package com.langkraft.ui.player

import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.AudioDownloader
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import com.langkraft.ui.BaseViewModel
import com.langkraft.ui.StateViewModel

import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.io.FileSystem
import com.langkraft.audio.AudioPlayer
import com.langkraft.di.AppConfig

class PlayerViewModel(
    private val contentRepository: LocalContentRepository,
    private val audioDownloader: AudioDownloader,
    private val vocabularyRepository: VocabularyRepository,
    linguisticAssistant: LinguisticAssistant,
    private val audioPlayer: AudioPlayer,
    private val fileSystem: FileSystem,
    private val appConfig: AppConfig
) : StateViewModel<PlayerState>(PlayerState()) {

    private val linguisticDelegate = PlayerLinguisticDelegate(linguisticAssistant, scope, ::updateState, { currentState })
    private val offlineDelegate = OfflineDownloadDelegate(audioDownloader, contentRepository, scope, ::updateState, { currentState })

    init {
        // Observe player state
        scope.launch {
            audioPlayer.currentTimeMs.collect { timeMs ->
                handleTimeUpdate(timeMs)
            }
        }
        scope.launch {
            audioPlayer.isPlaying.collect { isPlaying ->
                updateState { it.copy(isPlaying = isPlaying) }
            }
        }
    }

    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.LoadContent -> loadContent(event.contentId)
            is PlayerEvent.PlayPause -> {
                if (audioPlayer.isPlaying.value) audioPlayer.pause() else audioPlayer.play()
            }
            is PlayerEvent.ToggleLoop -> {
                updateState { it.copy(isLooping = !it.isLooping) }
            }
            is PlayerEvent.ToggleOffline -> {
                offlineDelegate.handleToggleOffline()
            }
            is PlayerEvent.SetPlaybackSpeed -> {
                updateState { it.copy(playbackSpeed = event.speed) }
                audioPlayer.setPlaybackSpeed(event.speed.toDouble())
            }
            is PlayerEvent.StepBack -> {
                val newTime = (currentState.currentTimeMs - 5000).coerceAtLeast(0)
                audioPlayer.seekTo(newTime)
                updateState { it.copy(currentTimeMs = newTime, isUserScrolling = false) }
            }
            is PlayerEvent.SeekTo -> {
                audioPlayer.seekTo(event.timeMs)
                updateState { it.copy(currentTimeMs = event.timeMs, isUserScrolling = false) }
            }
            is PlayerEvent.UserScrollStarted -> {
                updateState { it.copy(isUserScrolling = true, lastUserInteractionTime = System.currentTimeMillis()) }
            }
            is PlayerEvent.WordClicked -> {
                linguisticDelegate.handleWordClicked(event.word, event.line)
            }
            is PlayerEvent.DeepAnalysisClicked -> {
                linguisticDelegate.handleDeepAnalysis(event.line)
            }
            is PlayerEvent.MemorizationClicked -> {
                updateState { it.copy(memorizationText = event.text) }
            }
            is PlayerEvent.ToggleTranslation -> {
                linguisticDelegate.handleToggleTranslation(event.line)
            }
            is PlayerEvent.ToggleLemmatization -> {
                linguisticDelegate.handleToggleLemmatization(event.line)
            }
            is PlayerEvent.DismissWordDetails -> {
                updateState { it.copy(selectedWord = null, wordTranslation = null) }
            }
            is PlayerEvent.DismissDeepAnalysis -> {
                updateState { it.copy(deepAnalysis = null) }
            }
            is PlayerEvent.DismissMemorization -> {
                updateState { it.copy(memorizationText = null) }
            }
        }
    }

    fun saveWord(word: VocabularyWord) {
        scope.launch {
            vocabularyRepository.saveWord(word)
            updateState { it.copy(selectedWord = null, wordTranslation = null) }
        }
    }

    private fun loadContent(id: String) {
        scope.launch {
            updateState { it.copy(isLoading = true) }
            val content = contentRepository.getContentById(id)
            if (content == null) {
                updateState { it.copy(isLoading = false, error = "Content not found") }
                return@launch
            }
            updateState { it.copy(isLoading = false, content = content) }
            
            audioPlayer.load(content.getPlaybackUrl(appConfig.backendUrl))
        }
    }
    
    private fun handleTimeUpdate(timeMs: Long) {
        val state = currentState
        
        // Handle User Scroll timeout (3 seconds)
        val shouldResetScroll = state.isUserScrolling && 
            (System.currentTimeMillis() - state.lastUserInteractionTime > 3000)

        val subtitles = state.content?.subtitles ?: emptyList()
        val newIndex = subtitles.indexOfFirst { timeMs >= it.startMs && timeMs <= it.endMs }

        if (state.isLooping && state.content != null) {
            val currentLine = if (newIndex != -1) subtitles[newIndex] else null
            
            if (currentLine != null) {
                if (timeMs >= currentLine.endMs - 300) {
                    audioPlayer.seekTo(currentLine.startMs)
                    updateState { it.copy(currentTimeMs = currentLine.startMs, currentSubtitleIndex = newIndex) }
                    return
                }
            } else {
                val lastLine = subtitles.findLast { it.endMs < timeMs && timeMs < it.endMs + 300 }
                if (lastLine != null) {
                    audioPlayer.seekTo(lastLine.startMs)
                    val lastLineIndex = subtitles.indexOf(lastLine)
                    updateState { it.copy(currentTimeMs = lastLine.startMs, currentSubtitleIndex = lastLineIndex) }
                    return
                }
            }
        }

        updateState { 
            it.copy(
                currentTimeMs = timeMs,
                currentSubtitleIndex = newIndex,
                isUserScrolling = if (shouldResetScroll) false else it.isUserScrolling
            ) 
        }
    }
}
