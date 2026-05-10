package com.langkraft.ui.content

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.ContentRepository
import com.langkraft.domain.usecase.IngestContentUseCase
import kotlinx.coroutines.CoroutineScope
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
    private val contentRepository: ContentRepository,
    private val ingestContentUseCase: IngestContentUseCase,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(ContentSelectionState())
    val state: StateFlow<ContentSelectionState> = _state.asStateFlow()

    init {
        loadLibrary()
    }

    private fun loadLibrary() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            contentRepository.getAllContent().collect { items ->
                _state.update { it.copy(library = items, isLoading = false) }
            }
        }
    }

    fun importContent(url: String) {
        scope.launch {
            _state.update { it.copy(isImporting = true) }
            val result = ingestContentUseCase(url)
            _state.update { it.copy(isImporting = false) }
            
            result.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }
}
