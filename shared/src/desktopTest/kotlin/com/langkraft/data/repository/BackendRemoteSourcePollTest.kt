package com.langkraft.data.repository

import com.langkraft.domain.model.ContentProcessingStatus
import com.langkraft.domain.model.IngestResponse
import com.langkraft.domain.model.IngestionJob
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackendRemoteSourcePollTest {

    @Test
    fun pollTimesOutWhenJobNeverReachesTerminalStatus() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/api/ingest") && !path.contains("job-") ->
                    json.encodeToString(IngestResponse("job-stuck"))
                else -> json.encodeToString(
                    IngestionJob(
                        jobId = "job-stuck",
                        status = ContentProcessingStatus.DOWNLOADING_AUDIO,
                        url = "https://youtu.be/example",
                        content = null,
                        error = null
                    )
                )
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val source = BackendRemoteSource(client, Url("http://localhost"), pollTimeoutMs = 50)

        val error = assertFailsWith<Exception> {
            source.fetchFromYouTube("https://youtu.be/example")
        }
        assertTrue(error.message!!.contains("timed out"), error.message)
        client.close()
    }
}
