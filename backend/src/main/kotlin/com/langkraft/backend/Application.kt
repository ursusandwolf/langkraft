package com.langkraft.backend

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import java.io.File

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    val downloadsDir = "downloads"
    val ingestionService = YouTubeIngestionService(downloadsDir)

    routing {
        get("/") {
            call.respondText("Langkraft Backend is running")
        }

        post("/api/ingest") {
            val url = call.parameters["url"] ?: return@post call.respondText("Missing URL", status = io.ktor.http.HttpStatusCode.BadRequest)
            try {
                val content = ingestionService.ingest(url)
                call.respond(content)
            } catch (e: Exception) {
                call.respondText("Error: ${e.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
            }
        }

        // Serve downloaded media
        staticFiles("/api/media", File(downloadsDir))
    }
}
