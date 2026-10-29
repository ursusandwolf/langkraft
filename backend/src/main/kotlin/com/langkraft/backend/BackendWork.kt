package com.langkraft.backend

import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Move whole synchronous operations, never a suspended half of an Exposed transaction. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackendWork(
    passwordParallelism: Int = 2,
    private val jdbcDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    init { require(passwordParallelism in 1..16) { "auth.max_concurrent_hashes must be between 1 and 16" } }
    // A shared view for both registration and verification; no owned executor needs shutdown.
    private val passwordDispatcher = Dispatchers.Default.limitedParallelism(passwordParallelism)

    suspend fun <T> jdbc(block: () -> T): T = withContext(jdbcDispatcher) {
        ensureActive()
        block()
    }

    suspend fun <T> password(block: () -> T): T = withContext(passwordDispatcher) {
        ensureActive()
        block()
    }

    companion object {
        fun from(config: ApplicationConfig): BackendWork {
            val value = config.propertyOrNull("auth.max_concurrent_hashes")?.getString() ?: "2"
            return BackendWork(value.toIntOrNull() ?: error("Invalid auth.max_concurrent_hashes"))
        }
    }
}
