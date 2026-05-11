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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.io.File
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json as ClientJson

@Serializable
data class IngestRequest(val url: String)

@Serializable
data class TranslateRequest(val word: String, val context: String)

@Serializable
data class TextRequest(val text: String)

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    // Dependency Management (Manual DI)
    val downloadsDir = "downloads"
    val ytdlpClient = YtdlpClient(downloadsDir)
    val ingestionService = YouTubeIngestionService(ytdlpClient)
    
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
            val request = call.receive<IngestRequest>()
            try {
                val content = ingestionService.ingest(request.url)
                call.respond(content)
            } catch (e: Exception) {
                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        post("/api/ai/translate-word") {
            val request = call.receive<TranslateRequest>()
            call.respond(aiAssistant.translateWord(request.word, request.context))
        }

        post("/api/ai/analyze-sentence") {
            val request = call.receive<TextRequest>()
            call.respond(aiAssistant.analyzeSentence(request.text))
        }

        post("/api/ai/correct-text") {
            val request = call.receive<TextRequest>()
            call.respond(aiAssistant.correctText(request.text))
        }

        // Serve downloaded media
        staticFiles("/api/media", File(downloadsDir))
    }
}
