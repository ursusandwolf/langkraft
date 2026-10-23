package com.langkraft.ui.srs

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.ReviewQuality
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.srs.SpacedRepetitionAlgorithm
import com.langkraft.ui.StateViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SrsTrainingState(
    val queue: List<VocabularyWord> = emptyList(),
    val currentWord: VocabularyWord? = null,
    val isAnswerVisible: Boolean = false,
    val remainingCount: Int = 0,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
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
        if (currentState.isLoading || currentState.isSaving) return
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
        updateState { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                // Take only first emission to avoid UI jumps during session
                val words = vocabularyRepository.getWordsToReview().first()
                ensureActive()
                updateState { 
                    it.copy(
                        queue = words,
                        currentWord = words.firstOrNull(),
                        remainingCount = words.size,
                        isFinished = words.isEmpty()
                    ) 
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(error = "Could not load your reviews. Please try again.") }
            } finally {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private fun handleSubmitResult(quality: ReviewQuality) {
        val word = currentState.currentWord ?: return
        updateState { it.copy(isSaving = true, error = null) }
        scope.launch {
            try {
                val updatedWord = srsAlgorithm.calculateNextReview(word, quality)
                vocabularyRepository.saveWord(updatedWord)
                ensureActive()
                // Advance only after persistence; a failed save leaves the same card retryable.
                updateState { state ->
                    val newQueue = state.queue.drop(1) +
                        if (quality == ReviewQuality.AGAIN) listOf(updatedWord) else emptyList()
                    state.copy(
                        queue = newQueue,
                        currentWord = newQueue.firstOrNull(),
                        remainingCount = newQueue.size,
                        isAnswerVisible = false,
                        isFinished = newQueue.isEmpty()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(error = "Could not save your review. Please choose a rating again.") }
            } finally {
                updateState { it.copy(isSaving = false) }
            }
        }
    }
}
