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
) : BaseViewModel() {
    private val _state = MutableStateFlow(WritingState())
    val state: StateFlow<WritingState> = _state.asStateFlow()

    fun onTextChanged(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun submitForCorrection() {
        val text = _state.value.inputText.trim()
        if (text.isBlank()) return

        _state.update { it.copy(isAnalyzing = true, error = null) }
        scope.launch {
            try {
                val result = linguisticAssistant.correctText(text)
                _state.update { it.copy(correction = result, isAnalyzing = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isAnalyzing = false, error = "Korrektur fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun toggleMemorization(show: Boolean) {
        _state.update { it.copy(showMemorization = show) }
    }

    fun clearCorrection() {
        _state.update { it.copy(correction = null, showMemorization = false) }
    }
}
