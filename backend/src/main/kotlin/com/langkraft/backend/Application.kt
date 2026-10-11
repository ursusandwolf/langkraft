package com.langkraft.backend

import com.langkraft.domain.model.ApiError
import com.langkraft.domain.model.IngestRequest
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import java.io.File

fun main() {
    val downloads = File(System.getenv("LANGKRAFT_DOWNLOADS_DIR") ?: "downloads")
    embeddedServer(Netty, host = System.getenv("LANGKRAFT_HOST") ?: "127.0.0.1",
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080) {
        langkraftModule(downloads, YouTubeIngestionService(downloads))
    }.start(wait = true)
}

fun Application.langkraftModule(downloads: File, ingestion: ContentIngestion) {
    install(ContentNegotiation) { json() }
    install(PartialContent)
    install(CORS) {
        allowHost("localhost:8081")
        allowHost("127.0.0.1:8081")
        allowMethod(HttpMethod.Post)
        allowHeader("Content-Type")
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError(cause.message ?: "Invalid request"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON request"))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            environment.log.error("Request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("Import failed. Verify German subtitles, yt-dlp and FFmpeg."))
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
        post("/api/ingest") {
            val request = call.receive<IngestRequest>()
            validateYouTubeUrl(request.url)
            call.respond(ingestion.ingest(request.url))
        }
        get("/api/media/{file}") {
            val name = call.parameters["file"].orEmpty()
            val match = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.opus").matchEntire(name)
            if (match == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Media not found"))
                return@get
            }
            val file = File(File(downloads, match.groupValues[1]), "audio.opus")
            if (!file.isFile) call.respond(HttpStatusCode.NotFound, ApiError("Media not found"))
            else call.respondFile(file)
        }
    }
}