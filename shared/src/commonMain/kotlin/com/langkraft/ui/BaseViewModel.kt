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
    protected val scope = CoroutineScope(baseContext + SupervisorJob(baseContext[kotlinx.coroutines.Job]))
    private var cleared = false
    internal var onClearedCallback: (() -> Unit)? = null

    fun onCleared() {
        if (cleared) return
        cleared = true
        try { disposeResources() }
        finally { scope.cancel(); onClearedCallback?.invoke(); onClearedCallback = null }
    }

    protected open fun disposeResources() {}
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

    val currentState: S get() = _state.value
}
