package com.langkraft.ui.player

import com.langkraft.audio.AudioPlayer
import com.langkraft.audio.AudioErrorReason
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.repository.ContentRepository
import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class PlayerViewModel(
    private val contentRepository: ContentRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val linguisticAssistant: LinguisticAssistant,
    viewModelScope: CoroutineScope,
    private val audioPlayer: AudioPlayer
) {
    private val scope = CoroutineScope(
        viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job])
    )
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var translationJob: Job? = null
    private var wordRequest = 0L
    private var lastContentId: String? = null
    private var audioRequest = 0L
    private var activeAudioRequest: Long? = null

    init {
        scope.launch {
            audioPlayer.error.collect { error ->
                if (error != null && error.requestId == activeAudioRequest) {
                    _state.update {
                        val missingLocal = error.reason == AudioErrorReason.LOCAL_ASSET_UNAVAILABLE &&
                            !it.isUsingRemoteAudio && it.content?.localAudioPath != null
                        val canPlayOnline = missingLocal && !it.content?.audioUrl.isNullOrBlank()
                        it.copy(error = when {
                            canPlayOnline -> "Сохранённое аудио недоступно. Можно воспроизвести по сети."
                            missingLocal -> "Сохранённое аудио недоступно."
                            else -> error.message
                        }, canPlayOnline = canPlayOnline)
                    }
                }
            }
        }
        scope.launch {
            audioPlayer.currentTimeMs.collect { time ->
                _state.update { it.copy(currentTimeMs = time) }
            }
        }
        scope.launch {
            audioPlayer.isPlaying.collect { playing ->
                _state.update { it.copy(isPlaying = playing) }
            }
        }
    }

    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.LoadContent -> loadContent(event.contentId)
            PlayerEvent.PlayPause -> controlAudio {
                if (audioPlayer.isPlaying.value) audioPlayer.pause() else audioPlayer.play()
            }
            PlayerEvent.PlayOnline -> playOnline()
            is PlayerEvent.SeekTo -> controlAudio { audioPlayer.seekTo(event.timeMs.coerceAtLeast(0)) }
            is PlayerEvent.WordClicked -> handleWordClicked(event.word, event.line)
            PlayerEvent.DismissWordDetails -> dismissWordDetails()
        }
    }

    fun retryLoad() {
        lastContentId?.let { loadContent(it, state.value.isUsingRemoteAudio) }
    }

    fun retryTranslation() {
        val current = state.value
        val word = current.selectedWord ?: return
        val line = current.selectedWordContext ?: return
        handleWordClicked(word, line)
    }

    private fun controlAudio(action: () -> Unit) {
        if (state.value.content == null || state.value.isLoading || state.value.error != null) return
        try {
            action()
        } catch (exception: Exception) {
            _state.update { it.copy(error = exception.message ?: "Unable to control audio") }
        }
    }

    private fun handleWordClicked(word: String, line: SubtitleLine) {
        translationJob?.cancel()
        val request = ++wordRequest
        _state.update {
            it.copy(selectedWord = word, selectedWordContext = line, translation = null,
                isTranslating = true, wordError = null)
        }
        translationJob = scope.launch {
            try {
                val translation = linguisticAssistant.translateWithContext(word, line.textDe)
                coroutineContext.ensureActive()
                if (request == wordRequest) {
                    _state.update { it.copy(translation = translation, isTranslating = false) }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (request == wordRequest) {
                    _state.update {
                        it.copy(isTranslating = false, wordError = exception.message ?: "Translation failed")
                    }
                }
            }
        }
    }

    private fun dismissWordDetails() {
        translationJob?.cancel()
        ++wordRequest
        _state.update {
            it.copy(selectedWord = null, selectedWordContext = null, translation = null,
                isTranslating = false, wordError = null)
        }
    }

    fun saveWord(word: VocabularyWord) {
        if (state.value.isSaving) return
        val request = wordRequest
        _state.update { it.copy(isSaving = true, wordError = null) }
        scope.launch {
            try {
                vocabularyRepository.saveWord(word)
                coroutineContext.ensureActive()
                if (request == wordRequest) dismissWordDetails()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (request == wordRequest) {
                    _state.update { it.copy(wordError = exception.message ?: "Unable to save word") }
                }
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun startAudio(content: ImmersionContent, useRemote: Boolean) {
        val request = ++audioRequest
        activeAudioRequest = request
        _state.update {
            it.copy(content = content, isLoading = false, error = null, canPlayOnline = false,
                isUsingRemoteAudio = useRemote)
        }
        audioPlayer.load(if (useRemote) content.audioUrl else requireNotNull(content.localAudioPath), request)
    }

    private fun playOnline() {
        val current = state.value
        val content = current.content ?: return
        if (!current.canPlayOnline || current.isUsingRemoteAudio) return
        try {
            // Keep loading/play synchronous with the user's click for browser autoplay policies.
            startAudio(content, useRemote = true)
            audioPlayer.play()
        } catch (exception: Exception) {
            _state.update { it.copy(error = exception.message ?: "Unable to play audio", canPlayOnline = false) }
        }
    }

    private fun loadContent(id: String, useRemote: Boolean = false) {
        lastContentId = id
        loadJob?.cancel()
        activeAudioRequest = null
        dismissWordDetails()
        _state.update { it.copy(isLoading = true, content = null, currentTimeMs = 0, error = null,
            canPlayOnline = false, isUsingRemoteAudio = useRemote) }
        loadJob = scope.launch {
            try {
                audioPlayer.pause()
                val content = contentRepository.getContentById(id)
                    ?: error("Content not found")
                coroutineContext.ensureActive()
                startAudio(content, useRemote || content.localAudioPath == null)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                coroutineContext.ensureActive()
                _state.update {
                    it.copy(isLoading = false, error = exception.message ?: "Unable to load content")
                }
            }
        }
    }

    /** Call when the owning screen/controller is disposed; the caller's scope is left intact. */
    fun release() {
        scope.cancel()
        audioPlayer.release()
    }
}
