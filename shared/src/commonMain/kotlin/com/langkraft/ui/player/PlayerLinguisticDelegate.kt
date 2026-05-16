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
    private val updateState: ((PlayerState) -> PlayerState) -> Unit
) {
    fun handleWordClicked(word: String, line: SubtitleLine) {
        updateState { it.copy(selectedWord = word, selectedWordContext = line, isTranslatingWord = true) }
        scope.launch {
            try {
                val result = linguisticAssistant.translateWord(word, line.originalText)
                updateState { it.copy(wordTranslation = result, isTranslatingWord = false) }
            } catch (e: Exception) {
                updateState { it.copy(isTranslatingWord = false, error = e.message) }
            }
        }
    }

    fun handleDeepAnalysis(line: SubtitleLine) {
        updateState { it.copy(isAnalyzing = true) }
        scope.launch {
            try {
                val result = linguisticAssistant.analyzeSentence(line.originalText)
                updateState { it.copy(deepAnalysis = result, isAnalyzing = false) }
            } catch (e: Exception) {
                updateState { it.copy(isAnalyzing = false, error = e.message) }
            }
        }
    }

    fun handleToggleLemmatization(line: SubtitleLine) {
        // Since we don't have direct access to state here, we'll check it within updateState or pass a getter
        // But for simplicity, we can just use updateState to check and update
        updateState { state ->
            val current = state.lemmatizedSentences[line.id]
            if (current != null) {
                state.copy(lemmatizedSentences = state.lemmatizedSentences - line.id)
            } else {
                scope.launch {
                    try {
                        val result = linguisticAssistant.analyzeSentence(line.originalText)
                        val lemmas = result.words.associate { it.original to it.lemma }
                        updateState { 
                            it.copy(
                                lemmatizedSentences = it.lemmatizedSentences + (line.id to lemmas),
                                lemmatizingSentenceId = null
                            ) 
                        }
                    } catch (e: Exception) {
                        updateState { it.copy(lemmatizingSentenceId = null, error = e.message) }
                    }
                }
                state.copy(lemmatizingSentenceId = line.id)
            }
        }
    }

    fun handleToggleTranslation(line: SubtitleLine) {
        updateState { state ->
            val current = state.sentenceTranslations[line.id]
            if (current != null) {
                state.copy(sentenceTranslations = state.sentenceTranslations - line.id)
            } else {
                scope.launch {
                    try {
                        val translation = linguisticAssistant.translateSentence(line.originalText)
                        updateState { 
                            it.copy(
                                sentenceTranslations = it.sentenceTranslations + (line.id to translation),
                                analyzingSentenceId = null
                            ) 
                        }
                    } catch (e: Exception) {
                        updateState { it.copy(analyzingSentenceId = null, error = e.message) }
                    }
                }
                state.copy(analyzingSentenceId = line.id)
            }
        }
    }
}
