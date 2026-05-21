package com.langkraft.ui.srs

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.ReviewQuality
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.srs.SpacedRepetitionAlgorithm
import com.langkraft.ui.BaseViewModel
import com.langkraft.ui.StateViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SrsTrainingState(
    val queue: List<VocabularyWord> = emptyList(),
    val currentWord: VocabularyWord? = null,
    val isAnswerVisible: Boolean = false,
    val remainingCount: Int = 0,
    val isLoading: Boolean = false,
    val isFinished: Boolean = false
)

sealed class SrsEvent {
    object LoadQueue : SrsEvent()
    object ShowAnswer : SrsEvent()
    data class SubmitResult(val quality: ReviewQuality) : SrsEvent()
    object Restart : SrsEvent()
}

class SrsTrainingViewModel(
    private val vocabularyRepository: VocabularyRepository,
    private val srsAlgorithm: SpacedRepetitionAlgorithm,
    baseContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Main
) : StateViewModel<SrsTrainingState>(SrsTrainingState(), baseContext) {

    init {
        onEvent(SrsEvent.LoadQueue)
    }

    fun onEvent(event: SrsEvent) {
        when (event) {
            SrsEvent.LoadQueue -> loadQueue()
            SrsEvent.ShowAnswer -> updateState { it.copy(isAnswerVisible = true) }
            is SrsEvent.SubmitResult -> handleSubmitResult(event.quality)
            SrsEvent.Restart -> {
                updateState { SrsTrainingState() }
                loadQueue()
            }
        }
    }

    private fun loadQueue() {
        scope.launch {
            updateState { it.copy(isLoading = true) }
            try {
                // Take only first emission to avoid UI jumps during session
                val words = vocabularyRepository.getWordsToReview().first()
                updateState { 
                    it.copy(
                        queue = words,
                        currentWord = words.firstOrNull(),
                        remainingCount = words.size,
                        isLoading = false,
                        isFinished = words.isEmpty()
                    ) 
                }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private fun handleSubmitResult(quality: ReviewQuality) {
        val word = currentState.currentWord ?: return
        val updatedWord = srsAlgorithm.calculateNextReview(word, quality)
        
        scope.launch {
            vocabularyRepository.saveWord(updatedWord)
        }

        updateState { state ->
            val newQueue = state.queue.toMutableList()
            
            // Remove current word from the front
            if (newQueue.isNotEmpty()) {
                newQueue.removeAt(0)
            }

            // If quality is poor (AGAIN), add to the end of the queue for re-learning
            if (quality.sm2Value < 3) { // 3 is SUCCESS_THRESHOLD in Sm2Algorithm
                newQueue.add(updatedWord)
            }

            val nextWord = newQueue.firstOrNull()
            state.copy(
                queue = newQueue,
                currentWord = nextWord,
                remainingCount = newQueue.size,
                isAnswerVisible = false,
                isFinished = nextWord == null
            )
        }
    }
}
