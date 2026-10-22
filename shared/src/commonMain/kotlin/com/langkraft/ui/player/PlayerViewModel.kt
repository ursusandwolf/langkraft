package com.langkraft.ui.player

import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.AudioDownloader
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
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
import kotlinx.datetime.Clock

class PlayerViewModel(
    private val contentRepository: LocalContentRepository,
    private val audioDownloader: AudioDownloader,
    private val vocabularyRepository: VocabularyRepository,
    linguisticAssistant: LinguisticAssistant,
    private val audioPlayer: AudioPlayer,
    private val fileSystem: FileSystem,
    private val appConfig: AppConfig,
    baseContext: CoroutineContext = Dispatchers.Main
) : StateViewModel<PlayerState>(PlayerState(), baseContext) {

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var audioRequest = 0L
    private var saveInFlight = false

    private val linguisticDelegate = PlayerLinguisticDelegate(linguisticAssistant, scope, ::updateState, { currentState })
    private val offlineDelegate = OfflineDownloadDelegate(audioDownloader, contentRepository, scope, ::updateState, { currentState })

    init {
        scope.launch {
            audioPlayer.error.collect { failure ->
                if (failure != null && failure.requestId == audioRequest) updateState { it.copy(error = failure.message,
                    canPlayOnline = failure.reason == com.langkraft.audio.AudioErrorReason.LOCAL_ASSET_UNAVAILABLE && !it.isUsingRemoteAudio) }
            }
        }
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
                val subtitles = currentState.content?.subtitles ?: return
                val currentIndex = currentState.currentSubtitleIndex
                val currentTime = currentState.currentTimeMs
                
                val targetTime = if (currentIndex != -1) {
                    val currentLine = subtitles[currentIndex]
                    // If we are more than 1 second into the current line, jump to its start
                    if (currentTime - currentLine.startMs > 1000) {
                        currentLine.startMs
                    } else if (currentIndex > 0) {
                        // Otherwise jump to the previous line
                        subtitles[currentIndex - 1].startMs
                    } else {
                        0L
                    }
                } else {
                    // Not in a subtitle, find the closest one before current time
                    val prevLine = subtitles.findLast { it.endMs < currentTime }
                    prevLine?.startMs ?: 0L
                }
                
                audioPlayer.seekTo(targetTime)
                updateState { it.copy(currentTimeMs = targetTime, isUserScrolling = false) }
            }
            is PlayerEvent.SeekTo -> {
                audioPlayer.seekTo(event.timeMs)
                updateState { it.copy(currentTimeMs = event.timeMs, isUserScrolling = false) }
            }
            is PlayerEvent.UserScrollStarted -> {
                updateState { it.copy(isUserScrolling = true, lastUserInteractionTime = Clock.System.now().toEpochMilliseconds()) }
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
                linguisticDelegate.dismissWord()
            }
            is PlayerEvent.DismissDeepAnalysis -> {
                updateState { it.copy(deepAnalysis = null) }
            }
            is PlayerEvent.PlayOnline -> {
                currentState.content?.let { content ->
                    audioPlayer.load(content.copy(localAudioPath=null, downloadStatus=DownloadStatus.IDLE).getPlaybackUrl(appConfig.backendUrl), ++audioRequest)
                    updateState { it.copy(error=null, canPlayOnline=false, isUsingRemoteAudio=true) }
                    audioPlayer.play()
                }
            }
            is PlayerEvent.DismissError -> updateState { it.copy(error=null) }
            is PlayerEvent.DismissMemorization -> {
                updateState { it.copy(memorizationText = null) }
            }
        }
    }

    fun retryTranslation() {
        val word = currentState.selectedWord ?: return
        val line = currentState.selectedWordContext ?: return
        linguisticDelegate.handleWordClicked(word, line)
    }

    fun saveWord(word: VocabularyWord) {
        if (saveInFlight) return
        saveInFlight = true
        val selection = linguisticDelegate.selectionGeneration
        updateState { it.copy(isSavingWord = true, wordError = null) }
        scope.launch {
            try {
                vocabularyRepository.saveWord(word)
                if (selection == linguisticDelegate.selectionGeneration) linguisticDelegate.dismissWord()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (selection == linguisticDelegate.selectionGeneration) updateState { it.copy(wordError = exception.message ?: "Cannot save word") }
            } finally {
                saveInFlight = false
                updateState { it.copy(isSavingWord = false) }
            }
        }
    }

    private fun loadContent(id: String) {
        val generation = ++loadGeneration
        ++audioRequest
        loadJob?.cancel()
        linguisticDelegate.reset()
        audioPlayer.pause()
        updateState { PlayerState(isLoading = true) }
        loadJob = scope.launch {
            try {
                val content = contentRepository.getContentById(id)
                ensureActive()
                if (generation != loadGeneration) return@launch
                if (content == null) {
                    updateState { it.copy(isLoading = false, error = "Content not found") }
                    return@launch
                }
                updateState { it.copy(isLoading = false, content = content) }
                audioPlayer.load(content.getPlaybackUrl(appConfig.backendUrl), audioRequest)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation == loadGeneration) updateState { it.copy(isLoading = false, error = exception.message ?: "Cannot load content") }
            }
        }
    }

    override fun onCleared() {
        audioPlayer.release()
        super.onCleared()
    }
    
    private fun handleTimeUpdate(timeMs: Long) {
        val state = currentState
        
        // Handle User Scroll timeout (3 seconds)
        val shouldResetScroll = state.isUserScrolling && 
            (Clock.System.now().toEpochMilliseconds() - state.lastUserInteractionTime > 3000)

        val subtitles = state.content?.subtitles ?: emptyList()
        val newIndex = subtitles.indexOfFirst { timeMs >= it.startMs && timeMs < it.endMs }

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
