package com.langkraft.backend

import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.ai.TranslateRequest
import com.langkraft.domain.ai.TextRequest
import com.langkraft.domain.ai.SentenceTranslationResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

import com.langkraft.domain.model.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.*

import com.langkraft.backend.db.*
import at.favre.lib.crypto.bcrypt.BCrypt

fun Route.authRoutes() {
    val authService by inject<AuthService>()

    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()
            val response = authService.register(request)
            if (response == null) {
                call.respond(HttpStatusCode.Conflict, "User already exists")
            } else {
                call.respond(response)
            }
        }

        post("/login") {
            val request = call.receive<AuthRequest>()
            val response = authService.login(request)
            if (response == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            } else {
                call.respond(response)
            }
        }
    }
}

fun Route.apiRoutes() {
    val ingestionService by inject<YouTubeIngestionService>()
    val aiAssistant by inject<LinguisticAssistant>()
    val syncService by inject<SyncService>()
    val mediaStorage by inject<BackendMediaStorage>()

    // PUBLIC API ROUTES
    route("/api") {
        post("/ingest") {
            val request = call.receive<IngestRequest>()
            val jobId = ingestionService.startIngestion(request.url)
            call.respond(IngestResponse(jobId))
        }
        
        get("/ingest/{jobId}") {
            val jobId = call.parameters["jobId"]
            if (jobId == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing jobId")
                return@get
            }
            val job = ingestionService.getJobStatus(jobId)
            if (job == null) {
                call.respond(HttpStatusCode.NotFound, "Job not found")
                return@get
            }
            call.respond(job)
        }

        mediaRoutes(mediaStorage)
    }

    // AUTHENTICATED API ROUTES
    authenticate("auth-jwt") {
        route("/api") {
            route("/ai") {
                post("/translate-word") {
                    val request = call.receive<TranslateRequest>()
                    call.respond(aiAssistant.translateWord(request.word, request.context))
                }

                post("/translate-sentence") {
                    val request = call.receive<TextRequest>()
                    call.respond(SentenceTranslationResult(aiAssistant.translateSentence(request.text)))
                }

                post("/analyze-sentence") {
                    val request = call.receive<TextRequest>()
                    call.respond(aiAssistant.analyzeSentence(request.text))
                }

                post("/correct-text") {
                    val request = call.receive<TextRequest>()
                    call.respond(aiAssistant.correctText(request.text))
                }
            }
            
            post("/sync") {
                val principal = call.principal<JWTPrincipal>()
                val email = principal?.getClaim("email", String::class) ?: throw UnauthorizedException()
                val request = call.receive<SyncRequest>()
                val response = syncService.performSync(email, request)
                call.respond(response)
            }
        }
    }
}

fun StatusPagesConfig.configureBackendExceptions() {
    exception<MediaCapacityException> { call, cause ->
        call.response.headers.append("Retry-After", "5")
        call.respondText(cause.message.orEmpty(), status = HttpStatusCode.ServiceUnavailable)
    }
    exception<IngestionBusyException> { call, cause ->
        call.response.headers.append("Retry-After", "5")
        call.respondText(cause.message.orEmpty(), status = HttpStatusCode.ServiceUnavailable)
    }
    exception<UnauthorizedException> { call, cause ->
        call.respond(HttpStatusCode.Unauthorized, cause.message ?: "Unauthorized")
    }
    exception<IngestionException> { call, cause ->
        call.respondText("Ingestion Error: ${cause.message}", status = HttpStatusCode.BadRequest)
    }
    exception<AiException> { call, cause ->
        call.respondText("AI Error: ${cause.message}", status = HttpStatusCode.InternalServerError)
    }
    exception<Exception> { call, cause ->
        if (cause is kotlinx.coroutines.CancellationException) throw cause
        call.respondText("Internal Error: ${cause.message}", status = HttpStatusCode.InternalServerError)
    }
}
