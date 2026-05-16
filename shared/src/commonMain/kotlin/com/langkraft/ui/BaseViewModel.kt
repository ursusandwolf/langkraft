package com.langkraft.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.CoroutineContext

/**
 * Enhanced BaseViewModel with State management.
 */
abstract class BaseViewModel(
    baseContext: CoroutineContext = Dispatchers.Main
) {
    protected val scope = CoroutineScope(baseContext + SupervisorJob())

    open fun onCleared() {
        scope.cancel()
    }
}

/**
 * A ViewModel that manages a single State object.
 */
abstract class StateViewModel<S>(
    initialState: S,
    baseContext: CoroutineContext = Dispatchers.Main
) : BaseViewModel(baseContext) {
    
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    protected fun updateState(updater: (S) -> S) {
        _state.update(updater)
    }

    protected val currentState: S get() = _state.value
}
