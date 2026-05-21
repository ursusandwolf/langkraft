package com.langkraft.ui.player

import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.SubtitleLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PlayerLinguisticDelegate(
    private val linguisticAssistant: LinguisticAssistant,
    private val scope: CoroutineScope,
    private val updateState: ((PlayerState) -> PlayerState) -> Unit,
    private val getState: () -> PlayerState
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
        val state = getState()
        val isAlreadyLemmatized = state.lemmatizedSentences.containsKey(line.id)
        
        if (isAlreadyLemmatized) {
            updateState { it.copy(lemmatizedSentences = it.lemmatizedSentences - line.id) }
        } else {
            updateState { it.copy(lemmatizingSentenceId = line.id) }
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
        }
    }

    fun handleToggleTranslation(line: SubtitleLine) {
        val state = getState()
        val isAlreadyTranslated = state.sentenceTranslations.containsKey(line.id)

        if (isAlreadyTranslated) {
            updateState { it.copy(sentenceTranslations = it.sentenceTranslations - line.id) }
        } else {
            updateState { it.copy(analyzingSentenceId = line.id) }
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
        }
    }
}
