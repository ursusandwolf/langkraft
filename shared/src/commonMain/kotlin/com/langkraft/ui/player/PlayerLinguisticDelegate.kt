package com.langkraft.ui.player

import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.SubtitleLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlayerLinguisticDelegate(
    private val linguisticAssistant: LinguisticAssistant,
    private val scope: CoroutineScope,
    private val _state: MutableStateFlow<PlayerState>
) {
    fun handleWordClicked(word: String, line: SubtitleLine) {
        _state.update { it.copy(selectedWord = word, selectedWordContext = line, isTranslatingWord = true) }
        scope.launch {
            try {
                val result = linguisticAssistant.translateWord(word, line.originalText)
                _state.update { it.copy(wordTranslation = result, isTranslatingWord = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isTranslatingWord = false, error = e.message) }
            }
        }
    }

    fun handleDeepAnalysis(line: SubtitleLine) {
        _state.update { it.copy(isAnalyzing = true) }
        scope.launch {
            try {
                val result = linguisticAssistant.analyzeSentence(line.originalText)
                _state.update { it.copy(deepAnalysis = result, isAnalyzing = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isAnalyzing = false, error = e.message) }
            }
        }
    }

    fun handleToggleLemmatization(line: SubtitleLine) {
        val current = _state.value.lemmatizedSentences[line.id]
        if (current != null) {
            _state.update { it.copy(lemmatizedSentences = it.lemmatizedSentences - line.id) }
        } else {
            _state.update { it.copy(lemmatizingSentenceId = line.id) }
            scope.launch {
                try {
                    val result = linguisticAssistant.analyzeSentence(line.originalText)
                    val lemmas = result.words.associate { it.original to it.lemma }
                    _state.update { 
                        it.copy(
                            lemmatizedSentences = it.lemmatizedSentences + (line.id to lemmas),
                            lemmatizingSentenceId = null
                        ) 
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(lemmatizingSentenceId = null, error = e.message) }
                }
            }
        }
    }

    fun handleToggleTranslation(line: SubtitleLine) {
        val current = _state.value.sentenceTranslations[line.id]
        if (current != null) {
            _state.update { it.copy(sentenceTranslations = it.sentenceTranslations - line.id) }
        } else {
            _state.update { it.copy(analyzingSentenceId = line.id) }
            scope.launch {
                try {
                    val translation = linguisticAssistant.translateSentence(line.originalText)
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
}
