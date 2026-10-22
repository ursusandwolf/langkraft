package com.langkraft.backend

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class MediaRoutesTest {
    @TempDir lateinit var root: Path

    @Test fun applicationStartupRunsRetentionWithoutAnyImportRequests() = testApplication {
        val downloads = root.resolve("downloads")
        val old = BackendMediaStorage(downloads.toString(), now = { 1000 })
        val id = UUID.randomUUID().toString()
        old.beginImport(id).use {
            Files.writeString(downloads.resolve("$id/video.mp3"), "expired audio")
            old.publish(id)
        }
        environment { config = MapApplicationConfig(
            "jwt.secret" to "test-secret-with-at-least-thirty-two-characters",
            "jwt.issuer" to "tests", "jwt.audience" to "tests", "jwt.realm" to "test", "jwt.validity_ms" to "86400000",
            "storage.data_dir" to root.resolve("backend").toString(), "storage.downloads_dir" to downloads.toString(),
            "storage.media_retention_ms" to "100", "gemini.api_key" to "mock_key",
            "ingestion.cleanup_interval_ms" to "3600000", "ingestion.job_expiry_ms" to "86400000", "ingestion.worker_count" to "1"
        ) }
        application { module() }
        startApplication()
        assertFalse(Files.exists(downloads.resolve(id)), "Startup must clean expired media without a request to /ingest")
    }

    @Test fun getHeadRangeAndExpiredMediaKeepHttpContract() = testApplication {
        environment { config = MapApplicationConfig() }
        var now = 1000L
        val storage = BackendMediaStorage(root.toString(), 100, { now })
        val id = UUID.randomUUID().toString()
        storage.beginImport(id).use {
            Files.write(root.resolve("$id/video.mp3"), ByteArray(100) { it.toByte() })
            storage.publish(id)
        }
        Files.writeString(root.resolve("legacy.opus"), "legacy opus")
        application {
            install(PartialContent)
            routing { route("/api") { mediaRoutes(storage) } }
        }
        val path = "/api/media/$id/video.mp3"
        val response = client.get(path)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("audio/mpeg", response.headers[HttpHeaders.ContentType])
        assertEquals(100, response.body<ByteArray>().size)
        val head = client.head(path)
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals("100", head.headers[HttpHeaders.ContentLength])
        assertEquals(0, head.body<ByteArray>().size)
        val range = client.get(path) { header(HttpHeaders.Range, "bytes=5-9") }
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertContentEquals(byteArrayOf(5, 6, 7, 8, 9), range.body())
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, client.get(path) {
            header(HttpHeaders.Range, "bytes=200-300")
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/media/$id/.langkraft-media-v1").status)
        assertEquals("legacy opus", client.get("/api/media/legacy.opus").body<String>())
        now += 1000
        assertEquals(listOf(id), storage.cleanup().deleted, "Completed/HEAD/Range/416 requests must release leases")
        assertEquals(HttpStatusCode.NotFound, client.get(path).status)
    }

    @Test fun nettyKeepsFileDuringBackpressuredTransferAndReleasesOnDisconnect() = runBlocking {
        var now = 1000L
        val storage = BackendMediaStorage(root.toString(), 100, { now })
        val id = UUID.randomUUID().toString()
        val file = root.resolve("$id/video.mp3")
        storage.beginImport(id).use {
            Files.newOutputStream(file).use { output -> repeat(128) { output.write(ByteArray(256 * 1024)) } }
            storage.publish(id)
        }
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(PartialContent)
            routing { route("/api") { mediaRoutes(storage) } }
        }.start(wait = false)
        val client = HttpClient(CIO)
        try {
            val port = server.resolvedConnectors().single().port
            withTimeout(20_000) {
                client.prepareGet("http://127.0.0.1:$port/api/media/$id/video.mp3").execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val channel = response.bodyAsChannel()
                    channel.readFully(ByteArray(1), 0, 1)
                    now += 1000
                    assertTrue(storage.cleanup().deleted.isEmpty(), "Cleanup must skip an active real HTTP transfer")
                    assertTrue(Files.isRegularFile(file))
                    channel.cancel(null)
                }
                while (Files.exists(file)) { storage.cleanup(); delay(20) }
            }
        } finally { client.close(); server.stop(0, 5000) }
    }
}
