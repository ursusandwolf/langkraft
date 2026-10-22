package com.langkraft.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertTrue

class ClientDisconnectTest {
    @get:Rule val directory = TemporaryFolder()

    @Test(timeout = 30_000) fun disconnectCancelsTheHandlerAndRemovesPartialDownloads() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val service = YouTubeIngestionService(directory.root.path, runner = YtDlpRunner { arguments ->
            val output = arguments[arguments.indexOf("--output") + 1]
            File(output.replace("%(ext)s", "opus.part")).writeText("partial")
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        // Netty must not inherit runBlocking's single-thread event loop: blocking
        // server.stop() would prevent its response/shutdown coroutines from running.
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = serverScope.embeddedServer(Netty, host = "127.0.0.1", port = 0) { module(service) }.start()
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        try {
            val port = server.resolvedConnectors().single().port
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/ingest"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"url":"https://youtu.be/video"}"""))
                .build()
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            try {
                withTimeout(10_000) { started.await() }
                assertTrue(response.cancel(true))
                withTimeout(10_000) {
                    cancelled.await()
                    while (directory.root.listFiles().orEmpty().isNotEmpty()) delay(20)
                }
            } finally { response.cancel(true) }
        } finally {
            try { client.close(); server.stop(1000, 5000) }
            finally { withTimeout(10_000) { serverScope.coroutineContext.job.cancelAndJoin() } }
        }
    }
}
