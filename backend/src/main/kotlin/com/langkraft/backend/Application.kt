package com.langkraft.backend

import com.langkraft.backend.di.backendModule
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

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
    install(Koin) {
        slf4jLogger()
        modules(backendModule)
    }

    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        configureBackendExceptions()
    }

    routing {
        get("/") {
            call.respondText("Langkraft Backend is running")
        }
        
        apiRoutes()
    }
}
