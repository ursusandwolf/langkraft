package com.langkraft.ui.player

import com.langkraft.audio.AudioPlayer
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.repository.ContentRepository
import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class PlayerViewModel(
    private val contentRepository: ContentRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val linguisticAssistant: LinguisticAssistant,
    private val audioPlayer: AudioPlayer,
    scope: CoroutineScope,
    private val resolveAudio: suspend (String) -> String = { it },
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val playerScope = CoroutineScope(scope.coroutineContext + job)
    private val _state = MutableStateFlow(PlayerState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private var translationJob: Job? = null
    private var saveJob: Job? = null
    private var selectionVersion = 0L

    init {
        playerScope.launch { audioPlayer.currentTimeMs.collect { time -> _state.update { it.copy(currentTimeMs = time) } } }
        playerScope.launch { audioPlayer.isPlaying.collect { playing -> _state.update { it.copy(isPlaying = playing) } } }
        playerScope.launch { audioPlayer.error.collect { error -> if (error != null) _state.update { it.copy(error = error) } } }
    }

    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.LoadContent -> loadContent(event.contentId)
            PlayerEvent.PlayPause -> {
                if (_state.value.content != null) perform { if (audioPlayer.isPlaying.value) audioPlayer.pause() else audioPlayer.play() }
            }
            is PlayerEvent.SeekTo -> if (_state.value.content != null) perform { audioPlayer.seekTo(event.timeMs.coerceAtLeast(0)) }
            is PlayerEvent.WordClicked -> translate(event.word, event.line)
            PlayerEvent.DismissWordDetails -> dismissWord()
        }
    }

    private fun perform(action: () -> Unit) {
        try { action() } catch (failure: Exception) { _state.update { it.copy(error = failure.message ?: "Playback failed") } }
    }

    private fun dismissWord() {
        selectionVersion++
        translationJob?.cancel()
        _state.update { it.copy(selectedWord = null, selectedWordContext = null, translation = null, isTranslating = false) }
    }

    private fun translate(word: String, line: SubtitleLine) {
        translationJob?.cancel()
        val version = ++selectionVersion
        _state.update { it.copy(selectedWord = word, selectedWordContext = line, translation = null, isTranslating = true, error = null) }
        translationJob = playerScope.launch {
            try {
                val translation = linguisticAssistant.translateWithContext(word, line.textDe)
                if (version == selectionVersion) _state.update { it.copy(translation = translation, isTranslating = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (version == selectionVersion) _state.update { it.copy(isTranslating = false, error = failure.message ?: "Translation failed") }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun saveSelectedWord() {
        val selected = state.value
        val word = selected.selectedWord ?: return
        val context = selected.selectedWordContext ?: return
        val translation = selected.translation ?: return
        if (selected.isSaving) return
        val version = selectionVersion
        saveWord(VocabularyWord(Uuid.random().toString(), word, null, translation, context.textDe, context.contentId, context.id), version)
    }

    private fun saveWord(word: VocabularyWord, version: Long) {
        _state.update { it.copy(isSaving = true, error = null) }
        saveJob = playerScope.launch {
            try {
                vocabularyRepository.saveWord(word)
                if (version == selectionVersion) dismissWord()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update { it.copy(error = failure.message ?: "Saving failed") }
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun loadContent(id: String) {
        loadJob?.cancel()
        dismissWord()
        perform { audioPlayer.pause() }
        _state.update { PlayerState(isLoading = true) }
        loadJob = playerScope.launch {
            try {
                val content = contentRepository.getContentById(id) ?: error("Content not found")
                ensureActive()
                audioPlayer.load(resolveAudio(content.localAudioPath ?: content.audioUrl))
                _state.update { it.copy(isLoading = false, content = content, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update { it.copy(isLoading = false, error = failure.message ?: "Loading failed") }
            }
        }
    }

    fun close() {
        job.cancel()
        audioPlayer.release()
    }
}
