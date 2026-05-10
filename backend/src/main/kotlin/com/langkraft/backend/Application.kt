package com.langkraft.backend

import com.langkraft.backend.ai.GeminiLinguisticAssistant
import com.langkraft.domain.ai.MockLinguisticAssistant
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json as ClientJson

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
    
    val apiKey = System.getenv("GEMINI_API_KEY") ?: "mock_key"
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            ClientJson()
        }
    }
    
    val aiAssistant = if (apiKey == "mock_key") {
        MockLinguisticAssistant()
    } else {
        GeminiLinguisticAssistant(apiKey, httpClient)
    }

    routing {
        get("/") {
            call.respondText("Langkraft Backend is running")
        }

        post("/api/ingest") {
            val url = call.parameters["url"] ?: return@post call.respondText("Missing URL", status = HttpStatusCode.BadRequest)
            try {
                val content = ingestionService.ingest(url)
                call.respond(content)
            } catch (e: Exception) {
                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
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
