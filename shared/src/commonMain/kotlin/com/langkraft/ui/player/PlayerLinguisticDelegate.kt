package com.langkraft.ui.player

import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.SubtitleLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class PlayerLinguisticDelegate(
    private val linguisticAssistant: LinguisticAssistant,
    private val scope: CoroutineScope,
    private val updateState: ((PlayerState) -> PlayerState) -> Unit,
    private val getState: () -> PlayerState
) {
    var selectionGeneration = 0L
        private set
    private var wordJob: Job? = null
    private var contentGeneration = 0L
    /** Per-line counters so a late AI response cannot overwrite a newer toggle. */
    private val requests = mutableMapOf<String, Long>()

    fun dismissWord() {
        selectionGeneration++
        wordJob?.cancel()
        updateState {
            it.copy(
                selectedWord = null,
                selectedWordContext = null,
                wordTranslation = null,
                isTranslatingWord = false,
                wordError = null
            )
        }
    }

    fun reset() {
        contentGeneration++
        requests.clear()
        dismissWord()
    }

    fun handleWordClicked(word: String, line: SubtitleLine) {
        val generation = ++selectionGeneration
        wordJob?.cancel()
        updateState {
            it.copy(
                selectedWord = word,
                selectedWordContext = line,
                wordTranslation = null,
                wordError = null,
                isTranslatingWord = true
            )
        }
        wordJob = scope.launch {
            try {
                val result = linguisticAssistant.translateWord(word, line.originalText)
                ensureActive()
                if (generation != selectionGeneration) return@launch
                updateState { it.copy(wordTranslation = result, isTranslatingWord = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation != selectionGeneration) return@launch
                updateState {
                    it.copy(
                        isTranslatingWord = false,
                        wordError = exception.message ?: "Translation failed"
                    )
                }
            }
        }
    }

    fun handleDeepAnalysis(line: SubtitleLine) {
        updateState { it.copy(isAnalyzing = true) }
        val generation = contentGeneration
        scope.launch {
            try {
                val result = linguisticAssistant.analyzeSentence(line.originalText)
                ensureActive()
                if (generation != contentGeneration) return@launch
                updateState { it.copy(deepAnalysis = result, isAnalyzing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != contentGeneration) return@launch
                updateState { it.copy(isAnalyzing = false, error = e.message) }
            }
        }
    }

    fun handleToggleLemmatization(line: SubtitleLine) {
        if (getState().lemmatizedSentences.containsKey(line.id)) {
            updateState { it.copy(lemmatizedSentences = it.lemmatizedSentences - line.id) }
            return
        }

        updateState { it.copy(lemmatizingSentenceId = line.id) }
        val contentGen = contentGeneration
        val key = "lemma:${line.id}"
        val request = nextRequestId(key)
        scope.launch {
            try {
                val result = linguisticAssistant.analyzeSentence(line.originalText)
                ensureActive()
                if (isStale(contentGen, key, request)) return@launch
                val lemmas = result.words.associate { it.original to it.lemma }
                updateState {
                    it.copy(
                        lemmatizedSentences = it.lemmatizedSentences + (line.id to lemmas),
                        lemmatizingSentenceId = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isStale(contentGen, key, request)) return@launch
                updateState { it.copy(lemmatizingSentenceId = null, error = e.message) }
            }
        }
    }

    fun handleToggleTranslation(line: SubtitleLine) {
        if (getState().sentenceTranslations.containsKey(line.id)) {
            updateState { it.copy(sentenceTranslations = it.sentenceTranslations - line.id) }
            return
        }

        updateState { it.copy(analyzingSentenceId = line.id) }
        val contentGen = contentGeneration
        val key = "translation:${line.id}"
        val request = nextRequestId(key)
        scope.launch {
            try {
                val translation = linguisticAssistant.translateSentence(line.originalText)
                ensureActive()
                if (isStale(contentGen, key, request)) return@launch
                updateState {
                    it.copy(
                        sentenceTranslations = it.sentenceTranslations + (line.id to translation),
                        analyzingSentenceId = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isStale(contentGen, key, request)) return@launch
                updateState { it.copy(analyzingSentenceId = null, error = e.message) }
            }
        }
    }

    private fun nextRequestId(key: String): Long {
        val request = (requests[key] ?: 0L) + 1
        requests[key] = request
        return request
    }

    private fun isStale(contentGen: Long, key: String, request: Long): Boolean =
        contentGen != contentGeneration || requests[key] != request
}
