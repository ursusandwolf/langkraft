package com.langkraft.ui.player

import com.langkraft.domain.repository.ContentRepository
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
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.SubtitleLine

class PlayerViewModel(
    private val contentRepository: ContentRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val linguisticAssistant: LinguisticAssistant
) : BaseViewModel() {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.LoadContent -> loadContent(event.contentId)
            is PlayerEvent.PlayPause -> {
                _state.update { it.copy(isPlaying = !it.isPlaying) }
            }
            is PlayerEvent.ToggleLoop -> {
                _state.update { it.copy(isLooping = !it.isLooping) }
            }
            is PlayerEvent.SetPlaybackSpeed -> {
                _state.update { it.copy(playbackSpeed = event.speed) }
            }
            is PlayerEvent.SeekTo -> {
                _state.update { it.copy(currentTimeMs = event.timeMs) }
            }
            is PlayerEvent.WordClicked -> {
                handleWordClicked(event.word, event.line)
            }
            is PlayerEvent.DeepAnalysisClicked -> {
                handleDeepAnalysis(event.line)
            }
            is PlayerEvent.MemorizationClicked -> {
                _state.update { it.copy(memorizationText = event.text) }
            }
            is PlayerEvent.ToggleTranslation -> {
                handleToggleTranslation(event.line)
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

    private fun handleWordClicked(word: String, line: SubtitleLine) {
        _state.update { it.copy(selectedWord = word, selectedWordContext = line, isTranslatingWord = true) }
        scope.launch {
            try {
                val result = linguisticAssistant.translateWord(word, line.textDe)
                _state.update { it.copy(wordTranslation = result, isTranslatingWord = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isTranslatingWord = false, error = e.message) }
            }
        }
    }

    private fun handleDeepAnalysis(line: SubtitleLine) {
        _state.update { it.copy(isAnalyzing = true) }
        scope.launch {
            try {
                val result = linguisticAssistant.analyzeSentence(line.textDe)
                _state.update { it.copy(deepAnalysis = result, isAnalyzing = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isAnalyzing = false, error = e.message) }
            }
        }
    }

    private fun handleToggleTranslation(line: SubtitleLine) {
        val current = _state.value.sentenceTranslations[line.id]
        if (current != null) {
            _state.update { it.copy(sentenceTranslations = it.sentenceTranslations - line.id) }
        } else {
            _state.update { it.copy(analyzingSentenceId = line.id) }
            scope.launch {
                try {
                    val translation = linguisticAssistant.translateSentence(line.textDe)
                    _state.update { 
                        it.copy(
                            sentenceTranslations = it.sentenceTranslations + (line.id to translation),
                            analyzingSentenceId = null
                        ) 
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(analyzingSentenceId = null, error = e.message) }
                }
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
            _state.update { it.copy(isLoading = false, content = content) }
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
