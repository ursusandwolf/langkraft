package com.langkraft.ui.content

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.usecase.IngestContentUseCase
import com.langkraft.ui.BaseViewModel
import com.langkraft.ui.StateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContentSelectionState(
    val library: List<ImmersionContent> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val error: String? = null
)

class ContentSelectionViewModel(
    private val contentRepository: LocalContentRepository,
    private val ingestContentUseCase: IngestContentUseCase
) : StateViewModel<ContentSelectionState>(ContentSelectionState()) {

    init {
        loadLibrary()
    }

    private fun loadLibrary() {
        scope.launch {
            updateState { it.copy(isLoading = true) }
            contentRepository.getAllContent().collect { items ->
                updateState { it.copy(library = items, isLoading = false) }
            }
        }
    }

    fun importContent(url: String) {
        scope.launch {
            updateState { it.copy(isImporting = true) }
            val result = ingestContentUseCase(url)
            updateState { it.copy(isImporting = false) }
            
            result.onFailure { e ->
                updateState { it.copy(error = e.message) }
            }
        }
    }
}
