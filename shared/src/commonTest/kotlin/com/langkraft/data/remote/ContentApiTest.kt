package com.langkraft.data.remote

import com.langkraft.data.audio.AudioStorage
import com.langkraft.domain.model.ImmersionContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentApiTest {
    private val content = ImmersionContent("id", "Title", "/api/media/id.opus",
        "/server/private/audio.opus", "https://youtu.be/video", 1)

    @Test
    fun successfulHtmlAndJsonResponsesNeverReachAudioStorage() = runTest {
        for (type in listOf("text/html; charset=UTF-8", "application/json", "application/problem+json", "text/plain")) {
            var stored = false
            val client = HttpClient(MockEngine {
                respond("not audio", headers = headersOf(HttpHeaders.ContentType, type))
            })
            val storage = object : AudioStorage {
                override suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?): String {
                    stored = true
                    return "/offline/invalid.opus"
                }
            }
            try {
                val error = assertFailsWith<UnexpectedAudioContentTypeException> {
                    ContentApi(client, "https://backend").download(content, storage)
                }
                assertTrue(error.mediaType.startsWith(type.substringBefore(';')))
                assertFalse(stored, "Unexpected media type must be rejected before storage: $type")
            } finally { client.close() }
        }
    }

    @Test
    fun acceptsAudioOggAndGenericBinaryMediaTypesWithoutLosingBytes() = runTest {
        for (type in listOf("audio/ogg; codecs=opus", "audio/opus", "audio/wav", "application/ogg", "application/octet-stream")) {
            val bytes = "OggS-test-payload"
            val client = HttpClient(MockEngine {
                respond(bytes, headers = headersOf(HttpHeaders.ContentType, type))
            })
            var received: String? = null
            val storage = object : AudioStorage {
                override suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?): String {
                    received = source.readRemaining().readBytes().decodeToString()
                    return "/offline/id.opus"
                }
            }
            try {
                assertEquals("/offline/id.opus", ContentApi(client, "https://backend").download(content, storage))
                assertEquals(bytes, received)
            } finally { client.close() }
        }
    }

    @Test
    fun postsJsonAndResolvesMediaUrlWithoutUsingServerLocalPath() = runTest {
        val url = "https://youtu.be/video?value=\"quoted\""
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://backend/api/ingest", request.url.toString())
            val requestBody = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(url, requestBody["url"]!!.jsonPrimitive.content)
            respond(Json.encodeToString(content), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = ContentApi(client, "https://backend/").ingest(url)
            assertEquals("https://backend/api/media/id.opus", result.audioUrl)
            assertNull(result.localAudioPath)
        } finally { client.close() }
    }

    @Test
    fun streamsAudioWithLengthToStorage() = runTest {
        var stored = false
        val client = HttpClient(MockEngine { request ->
            assertEquals("https://backend/api/media/id.opus", request.url.toString())
            respond("opus", headers = headersOf(HttpHeaders.ContentLength, "4"))
        })
        val storage = object : AudioStorage {
            override suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?): String {
                assertEquals("id", contentId)
                assertEquals(4L, expectedBytes)
                assertEquals("opus", source.readRemaining().readBytes().decodeToString())
                stored = true
                return "/offline/id.opus"
            }
        }
        try {
            assertEquals("/offline/id.opus", ContentApi(client, "https://backend").download(content, storage))
            assertTrue(stored)
        } finally { client.close() }
    }

    @Test
    fun httpFailureIsReportedWithoutWritingAudio() = runTest {
        val client = HttpClient(MockEngine { respond("Unavailable", HttpStatusCode.BadGateway) })
        val storage = object : AudioStorage {
            override suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?) =
                error("Storage must not receive an error response")
        }
        try {
            val api = ContentApi(client, "https://backend")
            assertEquals(502, assertFailsWith<ContentApiException> { api.ingest("url") }.statusCode)
            assertEquals(502, assertFailsWith<ContentApiException> { api.download(content, storage) }.statusCode)
        } finally { client.close() }
    }

    @Test
    fun invalidJsonDoesNotBecomeContent() = runTest {
        val client = HttpClient(MockEngine { respond("""{"unexpected":"response"}""") })
        try {
            assertFailsWith<kotlinx.serialization.SerializationException> {
                ContentApi(client, "https://backend").ingest("url")
            }
        } finally { client.close() }
    }

    @Test
    fun cancellationFromHttpEngineIsPropagated() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("Cancelled") })
        try {
            assertFailsWith<CancellationException> {
                ContentApi(client, "https://backend").ingest("url")
            }
        } finally { client.close() }
    }
}
