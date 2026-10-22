package com.langkraft.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout

/** The owner closes the client after all repositories have stopped using it. */
fun <T : HttpClientEngineConfig> createContentHttpClient(
    engine: HttpClientEngineFactory<T>
): HttpClient = HttpClient(engine) {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        // An ingestion request may wait for the backend's ten-minute extraction.
        socketTimeoutMillis = 660_000
        requestTimeoutMillis = 660_000
    }
}
