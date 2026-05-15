package com.langkraft.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * Simple BaseViewModel to handle CoroutineScope in KMP.
 */
abstract class BaseViewModel(
    baseContext: CoroutineContext = Dispatchers.Main
) {
    protected val scope = CoroutineScope(baseContext + SupervisorJob())

    open fun onCleared() {
        scope.cancel()
    }
}
