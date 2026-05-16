package com.langkraft.ui.writing

import com.langkraft.domain.ai.CorrectionResult
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.ui.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WritingState(
    val inputText: String = "",
    val correction: CorrectionResult? = null,
    val isAnalyzing: Boolean = false,
    val showMemorization: Boolean = false,
    val error: String? = null
)

class WritingViewModel(
    private val linguisticAssistant: LinguisticAssistant
) : StateViewModel<WritingState>(WritingState()) {

    fun onTextChanged(text: String) {
        updateState { it.copy(inputText = text) }
    }

    fun submitForCorrection() {
        val text = currentState.inputText.trim()
        if (text.isBlank()) return

        updateState { it.copy(isAnalyzing = true, error = null) }
        scope.launch {
            try {
                val result = linguisticAssistant.correctText(text)
                updateState { it.copy(correction = result, isAnalyzing = false) }
            } catch (e: Exception) {
                updateState { it.copy(isAnalyzing = false, error = "Korrektur fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun toggleMemorization(show: Boolean) {
        updateState { it.copy(showMemorization = show) }
    }

    fun clearCorrection() {
        updateState { it.copy(correction = null, showMemorization = false) }
    }
}
