package com.langkraft.backend

import com.langkraft.domain.ai.TextRequest
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.jupiter.api.Test
import kotlin.test.*

class ApiRequestLimitsTest {
    @Test fun exactBodyLimitsAreAcceptedAndOneByteOverIsRejectedWithAndWithoutLength() = testApplication {
        environment { config = MapApplicationConfig() }
        var processed = 0
        var withoutLength = 0
        application {
            install(StatusPages) { configureBackendExceptions() }
            routing {
                post("/body/{limit}") {
                    if (call.request.headers[HttpHeaders.ContentLength] == null) withoutLength++
                    val body = call.receiveLimitedJson<TextRequest>(call.parameters["limit"]!!.toInt())
                    processed++
                    call.respondText(body.text)
                }
            }
        }
        for (limit in listOf(ApiLimits.AUTH_BODY_BYTES, ApiLimits.AI_BODY_BYTES, ApiLimits.SYNC_BODY_BYTES)) {
            for (streaming in listOf(false, true)) {
                val valid = """{"text":"ok"}""".padEnd(limit).toByteArray()
                val accepted = client.post("/body/$limit") {
                    if (streaming) setBody(stream(valid))
                    else { contentType(ContentType.Application.Json); setBody(valid) }
                }
                assertEquals(HttpStatusCode.OK, accepted.status)
                assertEquals("ok", accepted.bodyAsText())
                val rejected = client.post("/body/$limit") {
                    if (streaming) setBody(stream(valid + ' '.code.toByte()))
                    else { contentType(ContentType.Application.Json); setBody(valid + ' '.code.toByte()) }
                }
                assertEquals(HttpStatusCode.PayloadTooLarge, rejected.status)
            }
        }
        assertEquals(6, processed)
        assertEquals(6, withoutLength, "Streaming requests must exercise the path without Content-Length")
    }

    @Test fun oversizedMalformedBodyIsRejectedBeforeJsonAndDownstreamProcessing() = testApplication {
        environment { config = MapApplicationConfig() }
        var processed = 0
        application {
            install(StatusPages) { configureBackendExceptions() }
            routing { post("/body") {
                call.receiveLimitedJson<TextRequest>(ApiLimits.AUTH_BODY_BYTES)
                processed++
                call.respondText("processed")
            } }
        }
        val malformed = ("{secret-token" + "x".repeat(ApiLimits.AUTH_BODY_BYTES)).toByteArray()
        for (length in listOf(null, malformed.size.toLong(), 1L)) {
            val response = client.post("/body") { setBody(stream(malformed, length)) }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertFalse(response.bodyAsText().contains("secret-token"))
        }
        // A large advertised length must be rejected even when the actual bytes are small.
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/body") {
            setBody(stream("{}".toByteArray(), ApiLimits.AUTH_BODY_BYTES + 1L))
        }.status)
        assertEquals(0, processed)
    }

    @Test fun malformedJsonInvalidUtf8AndUnsupportedContentTypeNeverReachHandler() = testApplication {
        environment { config = MapApplicationConfig() }
        var processed = 0
        application {
            install(StatusPages) { configureBackendExceptions() }
            routing { post("/body") {
                call.receiveLimitedJson<TextRequest>(ApiLimits.AUTH_BODY_BYTES)
                processed++
                call.respondText("processed")
            } }
        }
        for (bytes in listOf("{secret-token".toByteArray(), byteArrayOf(),
            """{"text":"""".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray())) {
            val response = client.post("/body") { contentType(ContentType.Application.Json); setBody(bytes) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid request body", response.bodyAsText())
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post("/body") { setBody("plain text") }.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post("/body") {
            contentType(ContentType.parse("application/json; charset=UTF-16")); setBody("{}")
        }.status)
        assertEquals(0, processed)
    }

    @Test fun byteLimitCountsUtf8BytesRatherThanCharacters() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(StatusPages) { configureBackendExceptions() }
            routing { post("/body") {
                call.receiveLimitedJson<TextRequest>(32)
                call.respondText("processed")
            } }
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/body") {
            setBody(stream("""{"text":"${"я".repeat(16)}"}""".toByteArray()))
        }.status)
    }

    private fun stream(bytes: ByteArray, length: Long? = null) = object : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.Application.Json
        override val contentLength = length
        override suspend fun writeTo(channel: ByteWriteChannel) {
            for (offset in bytes.indices step 4096) {
                channel.writeFully(bytes, offset, minOf(4096, bytes.size - offset))
            }
        }
    }
}
