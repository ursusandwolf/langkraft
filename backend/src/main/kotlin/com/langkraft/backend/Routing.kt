package com.langkraft.backend

import com.langkraft.domain.ai.LinguisticAssistant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
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
import java.io.File

import com.langkraft.domain.model.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.*

import com.langkraft.backend.db.*
import at.favre.lib.crypto.bcrypt.BCrypt

fun Route.authRoutes() {
    val userRepository by inject<BackendUserRepository>()
    val jwtService by inject<JwtService>()

    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()
            val existing = userRepository.findByEmail(request.email)
            if (existing != null) {
                call.respond(HttpStatusCode.Conflict, "User already exists")
                return@post
            }
            
            val passwordHash = BCrypt.withDefaults().hashToString(12, request.passwordHash.toCharArray())
            val userId = userRepository.createUser(request.email, passwordHash, request.displayName)
            
            val response = AuthResponse(
                token = jwtService.generateToken(request.email),
                user = UserInfo(userId, request.email, request.displayName)
            )
            call.respond(response)
        }

        post("/login") {
            val request = call.receive<AuthRequest>()
            val user = userRepository.findByEmail(request.email)
            
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
                return@post
            }

            val result = BCrypt.verifyer().verify(request.passwordHash.toCharArray(), user.passwordHash)
            if (!result.verified) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
                return@post
            }
            
            val response = AuthResponse(
                token = jwtService.generateToken(request.email),
                user = UserInfo(user.id, user.email, user.displayName)
            )
            call.respond(response)
        }
    }
}

fun Route.apiRoutes() {
    val ingestionService by inject<YouTubeIngestionService>()
    val aiAssistant by inject<LinguisticAssistant>()
    val userRepository by inject<BackendUserRepository>()
    val vocabularyRepository by inject<VocabularySyncRepository>()

    authenticate("auth-jwt") {
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

            route("/ai") {
                post("/translate-word") {
                    val request = call.receive<TranslateRequest>()
                    call.respond(aiAssistant.translateWord(request.word, request.context))
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
                val email = principal?.getClaim("email", String::class)
                
                if (email == null) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
                
                val user = userRepository.findByEmail(email)
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
                
                val request = call.receive<SyncRequest>()
                val serverChanges = vocabularyRepository.sync(user.id, request.clientChanges, request.lastSyncTimestamp)
                
                call.respond(SyncResponse(System.currentTimeMillis(), serverChanges))
            }

            staticFiles("/media", File("downloads"))
        }
    }
}

fun StatusPagesConfig.configureBackendExceptions() {
    exception<IngestionException> { call, cause ->
        call.respondText("Ingestion Error: ${cause.message}", status = HttpStatusCode.BadRequest)
    }
    exception<AiException> { call, cause ->
        call.respondText("AI Error: ${cause.message}", status = HttpStatusCode.InternalServerError)
    }
    exception<Exception> { call, cause ->
        call.respondText("Internal Error: ${cause.message}", status = HttpStatusCode.InternalServerError)
    }
}
