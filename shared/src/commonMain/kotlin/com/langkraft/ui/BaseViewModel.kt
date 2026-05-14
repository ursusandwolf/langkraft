package com.langkraft.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Simple BaseViewModel to handle CoroutineScope in KMP.
 */
abstract class BaseViewModel {
    // Используем Unconfined для тестов, чтобы не падать на Dispatchers.Main
    protected val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    fun onCleared() {
        scope.cancel()
    }
}
