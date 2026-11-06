package com.langkraft.ui.player

import com.langkraft.audio.AudioErrorReason
import com.langkraft.data.getPlaybackUrl
import com.langkraft.di.AppConfig
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.repository.AudioDownloader
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.audio.AudioPlayer
import com.langkraft.ui.StateViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.coroutines.CoroutineContext

class PlayerViewModel(
    private val contentRepository: LocalContentRepository,
    audioDownloader: AudioDownloader,
    private val vocabularyRepository: VocabularyRepository,
    linguisticAssistant: LinguisticAssistant,
    private val audioPlayer: AudioPlayer,
    private val appConfig: AppConfig,
    baseContext: CoroutineContext = Dispatchers.Main
) : StateViewModel<PlayerState>(PlayerState(), baseContext) {

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var audioRequest = 0L
    private var saveInFlight = false

    private val linguisticDelegate = PlayerLinguisticDelegate(
        linguisticAssistant,
        scope,
        ::updateState,
        { currentState }
    )
    private val offlineDelegate = OfflineDownloadDelegate(
        audioDownloader,
        contentRepository,
        scope,
        ::updateState,
        { currentState }
    )

    init {
        scope.launch {
            audioPlayer.error.collect { failure ->
                if (failure == null || failure.requestId != audioRequest) return@collect
                updateState {
                    it.copy(
                        error = failure.message,
                        canPlayOnline = failure.reason == AudioErrorReason.LOCAL_ASSET_UNAVAILABLE && !it.isUsingRemoteAudio
                    )
                }
            }
        }
        scope.launch {
            audioPlayer.currentTimeMs.collect { timeMs -> handleTimeUpdate(timeMs) }
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
            is PlayerEvent.ToggleLoop -> updateState { it.copy(isLooping = !it.isLooping) }
            is PlayerEvent.ToggleOffline -> offlineDelegate.handleToggleOffline()
            is PlayerEvent.SetPlaybackSpeed -> {
                updateState { it.copy(playbackSpeed = event.speed) }
                audioPlayer.setPlaybackSpeed(event.speed.toDouble())
            }
            is PlayerEvent.StepBack -> stepBack()
            is PlayerEvent.SeekTo -> {
                audioPlayer.seekTo(event.timeMs)
                updateState { it.copy(currentTimeMs = event.timeMs, isUserScrolling = false) }
            }
            is PlayerEvent.UserScrollStarted -> {
                updateState {
                    it.copy(
                        isUserScrolling = true,
                        lastUserInteractionTime = Clock.System.now().toEpochMilliseconds()
                    )
                }
            }
            is PlayerEvent.WordClicked -> linguisticDelegate.handleWordClicked(event.word, event.line)
            is PlayerEvent.DeepAnalysisClicked -> linguisticDelegate.handleDeepAnalysis(event.line)
            is PlayerEvent.MemorizationClicked -> updateState { it.copy(memorizationText = event.text) }
            is PlayerEvent.ToggleTranslation -> linguisticDelegate.handleToggleTranslation(event.line)
            is PlayerEvent.ToggleLemmatization -> linguisticDelegate.handleToggleLemmatization(event.line)
            is PlayerEvent.DismissWordDetails -> linguisticDelegate.dismissWord()
            is PlayerEvent.DismissDeepAnalysis -> updateState { it.copy(deepAnalysis = null) }
            is PlayerEvent.PlayOnline -> playOnline()
            is PlayerEvent.DismissError -> updateState { it.copy(error = null) }
            is PlayerEvent.DismissMemorization -> updateState { it.copy(memorizationText = null) }
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
                if (selection == linguisticDelegate.selectionGeneration) {
                    linguisticDelegate.dismissWord()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (selection == linguisticDelegate.selectionGeneration) {
                    updateState { it.copy(wordError = exception.message ?: "Cannot save word") }
                }
            } finally {
                saveInFlight = false
                updateState { it.copy(isSavingWord = false) }
            }
        }
    }

    private fun stepBack() {
        val subtitles = currentState.content?.subtitles ?: return
        val targetTime = resolveStepBackTarget(
            subtitles = subtitles,
            currentIndex = currentState.currentSubtitleIndex,
            currentTimeMs = currentState.currentTimeMs
        )
        audioPlayer.seekTo(targetTime)
        updateState { it.copy(currentTimeMs = targetTime, isUserScrolling = false) }
    }

    private fun playOnline() {
        val content = currentState.content ?: return
        val remote = content.copy(localAudioPath = null, downloadStatus = DownloadStatus.IDLE)
        audioPlayer.load(remote.getPlaybackUrl(appConfig.backendUrl), ++audioRequest)
        updateState { it.copy(error = null, canPlayOnline = false, isUsingRemoteAudio = true) }
        audioPlayer.play()
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
                if (generation == loadGeneration) {
                    updateState { it.copy(isLoading = false, error = exception.message ?: "Cannot load content") }
                }
            }
        }
    }

    override fun onCleared() {
        audioPlayer.release()
        super.onCleared()
    }

    private fun handleTimeUpdate(timeMs: Long) {
        val state = currentState
        val shouldResetScroll = state.isUserScrolling &&
            (Clock.System.now().toEpochMilliseconds() - state.lastUserInteractionTime > USER_SCROLL_TIMEOUT_MS)

        val subtitles = state.content?.subtitles.orEmpty()
        val newIndex = subtitles.indexOfFirst { timeMs >= it.startMs && timeMs < it.endMs }

        if (state.isLooping && state.content != null) {
            val loop = resolveLoopSeek(timeMs, subtitles, newIndex)
            if (loop != null) {
                audioPlayer.seekTo(loop.seekMs)
                updateState {
                    it.copy(currentTimeMs = loop.seekMs, currentSubtitleIndex = loop.subtitleIndex)
                }
                return
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

    private companion object {
        const val USER_SCROLL_TIMEOUT_MS = 3_000L
    }
}

/**
 * Step-back target:
 * - inside a cue >1s past start → cue start
 * - otherwise previous cue (or 0)
 * - outside any cue → last cue that ended before now
 */
internal fun resolveStepBackTarget(
    subtitles: List<SubtitleLine>,
    currentIndex: Int,
    currentTimeMs: Long
): Long {
    if (currentIndex != -1) {
        val currentLine = subtitles[currentIndex]
        return when {
            currentTimeMs - currentLine.startMs > STEP_BACK_RESTART_THRESHOLD_MS -> currentLine.startMs
            currentIndex > 0 -> subtitles[currentIndex - 1].startMs
            else -> 0L
        }
    }
    return subtitles.findLast { it.endMs < currentTimeMs }?.startMs ?: 0L
}

internal data class LoopSeek(val seekMs: Long, val subtitleIndex: Int)

/** When looping near the end of a cue (or just past it), seek back to that cue's start. */
internal fun resolveLoopSeek(
    timeMs: Long,
    subtitles: List<SubtitleLine>,
    activeIndex: Int
): LoopSeek? {
    if (activeIndex != -1) {
        val currentLine = subtitles[activeIndex]
        if (timeMs < currentLine.endMs - LOOP_EDGE_MS) return null
        return LoopSeek(seekMs = currentLine.startMs, subtitleIndex = activeIndex)
    }
    val justEnded = subtitles.findLast { it.endMs < timeMs && timeMs < it.endMs + LOOP_EDGE_MS }
        ?: return null
    return LoopSeek(seekMs = justEnded.startMs, subtitleIndex = subtitles.indexOf(justEnded))
}

private const val STEP_BACK_RESTART_THRESHOLD_MS = 1_000L
private const val LOOP_EDGE_MS = 300L
