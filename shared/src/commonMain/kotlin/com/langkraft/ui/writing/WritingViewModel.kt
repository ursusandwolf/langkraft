package com.langkraft.ui.writing

import com.langkraft.domain.ai.CorrectionResult
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.ui.BaseViewModel
import com.langkraft.ui.StateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

data class WritingState(
    val aiAvailable: Boolean? = null,
    val inputText: String = "",
    val correction: CorrectionResult? = null,
    val isAnalyzing: Boolean = false,
    val showMemorization: Boolean = false,
    val error: String? = null
)

class WritingViewModel(
    private val linguisticAssistant: LinguisticAssistant,
    baseContext: CoroutineContext = Dispatchers.Main
) : StateViewModel<WritingState>(WritingState(), baseContext) {
    private var correctionJob: Job? = null
    private var generation = 0L

    init {
        scope.launch {
            val enabled = try { linguisticAssistant.capabilities().enabled }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            updateState { it.copy(aiAvailable = enabled) }
        }
    }

    private fun cancelCorrection() {
        generation++
        correctionJob?.cancel()
    }

    fun onTextChanged(text: String) {
        cancelCorrection()
        updateState { it.copy(
            inputText = text,
            correction = null,
            error = null,
            isAnalyzing = false
        ) }
    }

    fun submitForCorrection() {
        if (currentState.aiAvailable == false) return
        val text = currentState.inputText.trim()
        if (text.isBlank()) return

        cancelCorrection()
        val request = generation
        updateState { it.copy(isAnalyzing = true, error = null) }
        correctionJob = scope.launch {
            try {
                val result = linguisticAssistant.correctText(text)
                ensureActive()
                if (request != generation) return@launch
                updateState { it.copy(correction = result, isAnalyzing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request != generation) return@launch
                updateState { it.copy(isAnalyzing = false, error = "Korrektur fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun toggleMemorization(show: Boolean) {
        updateState { it.copy(showMemorization = show) }
    }

    fun clearCorrection() {
        cancelCorrection()
        updateState { it.copy(correction = null, showMemorization = false, isAnalyzing = false, error = null) }
    }
}
