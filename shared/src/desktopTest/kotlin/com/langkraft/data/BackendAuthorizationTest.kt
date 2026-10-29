package com.langkraft.data

import com.langkraft.data.sync.backendAuthorization
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BackendAuthorizationTest {
    @Test fun credentialsFollowCurrentSessionOnlyToBackendOrigin() = runTest {
        var token: String? = "first"
        val sent = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            sent += request.headers[HttpHeaders.Authorization]
            if (request.url.encodedPath == "/redirect") {
                return@MockEngine respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://external.test/redirected.mp3"))
            }
            respond("ok")
        }) { backendAuthorization(Url("https://backend.test:8443/proxy")) { token } }
        try {
            client.get("https://backend.test:8443/proxy/api/sync")
            token = "second"
            client.get("https://backend.test:8443/api/ai/correct-text")
            client.get("https://external.test/audio.mp3")
            client.get("http://backend.test:8443/audio.mp3")
            client.get("https://backend.test:9443/audio.mp3")
            client.get("https://backend.test:8443/redirect")
            token = null
            client.get("https://backend.test:8443/api/sync")
            assertEquals(listOf("Bearer first", "Bearer second", null, null, null, "Bearer second", null, null), sent)
        } finally { client.close() }
    }
}
