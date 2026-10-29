package com.langkraft.di

import com.langkraft.data.database.DatabaseHandle
import com.langkraft.data.sync.UserSession
import com.langkraft.ui.BaseViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.KoinApplication

/** A profile owns its ViewModels, account actions, HTTP requests and database. */
class ClientLifetime(baseContext: kotlin.coroutines.CoroutineContext = Dispatchers.Main) {
    private val job = SupervisorJob(baseContext[Job])
    val context = baseContext + job
    val scope = CoroutineScope(context)
    private val models = MutableStateFlow<Set<BaseViewModel>>(emptySet())

    fun <T : BaseViewModel> track(model: T): T {
        check(job.isActive) { "Local profile is closing" }
        models.update { it + model }
        model.onClearedCallback = { models.update { it - model } }
        return model
    }

    suspend fun close() {
        try { models.value.toList().forEach { it.onCleared() } }
        finally { job.cancelAndJoin() }
    }
}

class ClientRuntime(val application: KoinApplication) {
    val koin = application.koin
    private val lifetime = koin.get<ClientLifetime>()
    private val handle = koin.get<DatabaseHandle>()
    private val http = koin.get<HttpClient>()
    private var closed = false

    suspend fun restore() = koin.get<UserSession>().restore()

    suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            try { lifetime.close() } // Includes download compensation before SQL is closed.
            finally {
                try { http.close() }
                finally { try { handle.close() } finally { application.close() } }
            }
        }
    }
}
