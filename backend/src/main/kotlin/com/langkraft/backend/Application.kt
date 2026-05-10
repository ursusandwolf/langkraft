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

import com.langkraft.backend.ai.GeminiLinguisticAssistant
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.* as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.* as ClientJson

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    val downloadsDir = "downloads"
    val ingestionService = YouTubeIngestionService(downloadsDir)
    
    val apiKey = System.getenv("GEMINI_API_KEY") ?: "mock_key"
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json()
        }
    }
    
    val aiAssistant = if (apiKey == "mock_key") {
        com.langkraft.domain.ai.MockLinguisticAssistant()
    } else {
        GeminiLinguisticAssistant(apiKey, httpClient)
    }

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

        post("/api/ai/translate-word") {
            val word = call.parameters["word"] ?: ""
            val context = call.parameters["context"] ?: ""
            call.respond(aiAssistant.translateWord(word, context))
        }

        post("/api/ai/analyze-sentence") {
            val text = call.parameters["text"] ?: ""
            call.respond(aiAssistant.analyzeSentence(text))
        }

        post("/api/ai/correct-text") {
            val text = call.parameters["text"] ?: ""
            call.respond(aiAssistant.correctText(text))
        }

        // Serve downloaded media
        staticFiles("/api/media", File(downloadsDir))
    }
}
