package com.langkraft.backend

import com.langkraft.domain.ai.LinguisticAssistant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
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

fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()
            // Simplistic implementation for MVP
            val response = AuthResponse(
                token = generateToken(request.email),
                user = UserInfo(UUID.randomUUID().toString(), request.email, request.displayName)
            )
            call.respond(response)
        }

        post("/login") {
            val request = call.receive<AuthRequest>()
            // Simplistic implementation for MVP
            val response = AuthResponse(
                token = generateToken(request.email),
                user = UserInfo(UUID.randomUUID().toString(), request.email, "User")
            )
            call.respond(response)
        }
    }
}

private fun generateToken(email: String): String {
    return JWT.create()
        .withAudience("http://0.0.0.0:8080/api")
        .withIssuer("http://0.0.0.0:8080/")
        .withClaim("email", email)
        .withExpiresAt(Date(System.currentTimeMillis() + 3600000 * 24)) // 24h
        .sign(Algorithm.HMAC256("secret"))
}

fun Route.apiRoutes() {
    val ingestionService by inject<YouTubeIngestionService>()
    val aiAssistant by inject<LinguisticAssistant>()

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
                val request = call.receive<SyncRequest>()
                // Mock sync response
                call.respond(SyncResponse(System.currentTimeMillis(), emptyList()))
            }

            staticFiles("/media", File("downloads"))
        }
    }
}

fun StatusPages.Configuration.configureBackendExceptions() {
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
