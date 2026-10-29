package com.langkraft.di

import com.langkraft.ui.BaseViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ClientLifetimeTest {
    @Test fun shutdownWaitsForOldRequestsAndDownloadCompensationBeforeDatabaseClose() = runTest {
        val lifetime = ClientLifetime(StandardTestDispatcher(testScheduler))
        val compensating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val model = object : BaseViewModel(lifetime.context) {
            init { scope.launch {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { compensating.complete(Unit); release.await(); events += "compensated" } }
            } }
            override fun disposeResources() { events += "audio released" }
        }
        lifetime.track(model)
        val accountCancelled = CompletableDeferred<Unit>()
        lifetime.scope.launch { try { awaitCancellation() } finally { accountCancelled.complete(Unit) } }
        runCurrent()
        val closing = launch { lifetime.close(); events += "database can close" }
        runCurrent()
        assertTrue(compensating.isCompleted)
        assertTrue(accountCancelled.isCompleted)
        assertTrue(closing.isActive)
        assertFalse(events.contains("database can close"))
        release.complete(Unit)
        closing.join()
        model.onCleared() // Compose disposal after runtime shutdown must be harmless.
        assertEquals(listOf("audio released", "compensated", "database can close"), events)
    }
}
