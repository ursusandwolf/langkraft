package com.langkraft.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Simple BaseViewModel to handle CoroutineScope in KMP.
 * In a real project, this might be replaced by a library-specific ViewModel.
 */
abstract class BaseViewModel {
    protected val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun onCleared() {
        scope.cancel()
    }
}
