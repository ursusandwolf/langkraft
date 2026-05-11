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

fun Route.authRoutes() {
    val config = application.environment.config
    val jwtSecret = config.property("jwt.secret").getString()
    val jwtIssuer = config.property("jwt.issuer").getString()
    val jwtAudience = config.property("jwt.audience").getString()
    
    val userRepository by inject<BackendUserRepository>()

    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()
            val existing = userRepository.findByEmail(request.email)
            if (existing != null) {
                call.respond(HttpStatusCode.Conflict, "User already exists")
                return@post
            }
            
            val userId = userRepository.createUser(request.email, request.passwordHash, request.displayName)
            val response = AuthResponse(
                token = generateToken(request.email, jwtSecret, jwtIssuer, jwtAudience),
                user = UserInfo(userId, request.email, request.displayName)
            )
            call.respond(response)
        }

        post("/login") {
            val request = call.receive<AuthRequest>()
            val user = userRepository.findByEmail(request.email)
            
            if (user == null || user.passwordHash != request.passwordHash) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
                return@post
            }
            
            val response = AuthResponse(
                token = generateToken(request.email, jwtSecret, jwtIssuer, jwtAudience),
                user = UserInfo(user.id, user.email, user.displayName)
            )
            call.respond(response)
        }
    }
}

private fun generateToken(email: String, secret: String, issuer: String, audience: String): String {
    return JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withClaim("email", email)
        .withExpiresAt(Date(System.currentTimeMillis() + 3600000 * 24)) // 24h
        .sign(Algorithm.HMAC256(secret))
}

fun Route.apiRoutes() {
    val ingestionService by inject<YouTubeIngestionService>()
    val aiAssistant by inject<LinguisticAssistant>()
    val userRepository by inject<BackendUserRepository>()
    val vocabularyRepository by inject<BackendVocabularyRepository>()

    authenticate("auth-jwt") {
        route("/api") {
            post("/ingest") {
                val request = call.receive<IngestRequest>()
                val content = ingestionService.ingest(request.url)
                call.respond(content)
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
