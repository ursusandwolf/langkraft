package com.langkraft.ui.player

import com.langkraft.domain.repository.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.VocabularyWord

class PlayerViewModel(
    private val contentRepository: ContentRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val linguisticAssistant: LinguisticAssistant,
    private val viewModelScope: CoroutineScope
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.LoadContent -> loadContent(event.contentId)
            is PlayerEvent.PlayPause -> {
                _state.update { it.copy(isPlaying = !it.isPlaying) }
            }
            is PlayerEvent.SeekTo -> {
                _state.update { it.copy(currentTimeMs = event.timeMs) }
            }
            is PlayerEvent.WordClicked -> {
                handleWordClicked(event.word, event.line)
            }
            is PlayerEvent.DismissWordDetails -> {
                _state.update { it.copy(selectedWord = null, translation = null) }
            }
        }
    }

    private fun handleWordClicked(word: String, line: SubtitleLine) {
        _state.update { it.copy(selectedWord = word, selectedWordContext = line, isTranslating = true) }
        viewModelScope.launch {
            val translation = linguisticAssistant.translateWithContext(word, line.textDe)
            _state.update { it.copy(translation = translation, isTranslating = false) }
        }
    }

    fun saveWord(word: VocabularyWord) {
        viewModelScope.launch {
            vocabularyRepository.saveWord(word)
            _state.update { it.copy(selectedWord = null, translation = null) }
        }
    }


    private fun loadContent(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val content = contentRepository.getContentById(id)
            _state.update { it.copy(isLoading = false, content = content) }
        }
    }
    
    // This will be called by the actual audio player implementation
    fun updateTime(timeMs: Long) {
        _state.update { it.copy(currentTimeMs = timeMs) }
    }
}
