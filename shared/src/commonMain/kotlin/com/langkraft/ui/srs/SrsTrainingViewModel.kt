package com.langkraft.ui.srs

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.ReviewQuality
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.srs.SpacedRepetitionAlgorithm
import com.langkraft.ui.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SrsTrainingState(
    val queue: List<VocabularyWord> = emptyList(),
    val currentWord: VocabularyWord? = null,
    val isAnswerVisible: Boolean = false,
    val remainingCount: Int = 0,
    val isLoading: Boolean = false
)

class SrsTrainingViewModel(
    private val vocabularyRepository: VocabularyRepository,
    private val srsAlgorithm: SpacedRepetitionAlgorithm,
    baseContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Main
) : StateViewModel<SrsTrainingState>(SrsTrainingState(), baseContext) {

    init {
        loadQueue()
    }

    private fun loadQueue() {
        scope.launch {
            updateState { it.copy(isLoading = true) }
            vocabularyRepository.getWordsToReview().collect { words ->
                updateState { 
                    it.copy(
                        queue = words,
                        currentWord = words.firstOrNull(),
                        remainingCount = words.size,
                        isLoading = false,
                        isAnswerVisible = false
                    ) 
                }
            }
        }
    }

    fun showAnswer() {
        updateState { it.copy(isAnswerVisible = true) }
    }

    fun submitResult(quality: ReviewQuality) {
        val word = currentState.currentWord ?: return
        val updatedWord = srsAlgorithm.calculateNextReview(word, quality)
        
        scope.launch {
            vocabularyRepository.saveWord(updatedWord)
        }
    }
}
