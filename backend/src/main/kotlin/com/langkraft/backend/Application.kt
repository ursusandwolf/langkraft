package com.langkraft.backend

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class IngestRequest(val url: String)

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        module(YouTubeIngestionService(
            downloadsDir = System.getenv("DOWNLOADS_DIR") ?: "downloads",
            mediaBaseUrl = System.getenv("PUBLIC_BASE_URL") ?: "http://localhost:$port"
        ))
    }.start(wait = true)
}

fun Application.module(ingestionService: YouTubeIngestionService = YouTubeIngestionService()) {
    install(ContentNegotiation) { json() }
    install(PartialContent)
    routing {
        post("/api/ingest") {
            val request = call.receive<IngestRequest>()
            try {
                call.respond(call.cancelOnDisconnect { ingestionService.ingest(request.url) })
            } catch (exception: InvalidSourceUrlException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to exception.message))
            } catch (exception: IngestionException) {
                call.application.environment.log.warn("YouTube ingestion failed", exception)
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Unable to import video"))
            }
        }
        get("/api/media/{fileName}") {
            val file = ingestionService.mediaFile(call.parameters["fileName"].orEmpty())
            if (file == null) call.respond(HttpStatusCode.NotFound)
            else call.respondFile(file)
        }
    }
}
