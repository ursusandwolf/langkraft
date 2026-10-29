package com.langkraft.data.sync

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.bearerAuth
import io.ktor.http.Url

/** Downloads may use absolute third-party URLs; session credentials belong to the backend origin. */
internal fun HttpClientConfig<*>.backendAuthorization(backend: Url, token: () -> String?) {
    install(createClientPlugin("BackendAuthorization") {
        onRequest { request, _ ->
            if (request.url.protocol == backend.protocol && request.url.host == backend.host && request.url.port == backend.port) {
                token()?.let { request.bearerAuth(it) }
            }
        }
    })
}
