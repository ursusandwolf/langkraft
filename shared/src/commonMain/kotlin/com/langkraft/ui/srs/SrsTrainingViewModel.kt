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
    private val srsAlgorithm: SpacedRepetitionAlgorithm
) : BaseViewModel() {
    private val _state = MutableStateFlow(SrsTrainingState())
    val state: StateFlow<SrsTrainingState> = _state.asStateFlow()

    init {
        loadQueue()
    }

    private fun loadQueue() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            vocabularyRepository.getWordsToReview().collect { words ->
                _state.update { 
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
        _state.update { it.copy(isAnswerVisible = true) }
    }

    fun submitResult(quality: ReviewQuality) {
        val word = _state.value.currentWord ?: return
        val updatedWord = srsAlgorithm.calculateNextReview(word, quality)
        
        scope.launch {
            vocabularyRepository.saveWord(updatedWord)
            // SQLDelight flow will automatically emit the updated list
            // without the completed word, and loadQueue() will update the UI.
        }
    }
}
