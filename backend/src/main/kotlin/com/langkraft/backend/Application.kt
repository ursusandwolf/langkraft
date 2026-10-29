package com.langkraft.backend

import com.langkraft.backend.di.backendModule
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.ApplicationStopped
import org.koin.ktor.ext.get
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    install(PartialContent)
    val jwtSecret = environment.config.propertyOrNull("jwt.secret")?.getString()
        ?.takeIf { it.length >= 32 } ?: error("Set LANGKRAFT_JWT_SECRET to at least 32 characters")
    val jwtIssuer = environment.config.property("jwt.issuer").getString()
    val jwtAudience = environment.config.property("jwt.audience").getString()
    val jwtRealm = environment.config.property("jwt.realm").getString()

    install(Koin) {
        slf4jLogger()
        modules(backendModule, org.koin.dsl.module { single { environment.config } })
    }

    // Finish database preflight/migrations before allocating HTTP clients or background workers.
    get<org.jetbrains.exposed.sql.Database>()

    // Validate optional provider settings before accepting any requests.
    get<com.langkraft.domain.ai.LinguisticAssistant>()
    val aiHttpClient = get<io.ktor.client.HttpClient>()
    environment.monitor.subscribe(ApplicationStopped) { aiHttpClient.close() }

    // Maintenance must run even when this process only serves previously imported media.
    val ingestionService = get<YouTubeIngestionService>()
    environment.monitor.subscribe(ApplicationStopping) { ingestionService.close() }

    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        configureBackendExceptions()
    }

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtRealm
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.subject?.takeIf { it.isNotBlank() }
                if (credential.payload.audience.contains(jwtAudience) && userId != null &&
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        get<com.langkraft.backend.db.BackendUserRepository>().findById(userId) != null
                    }) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }

    routing {
        get("/") {
            call.respondText("Langkraft Backend is running")
        }
        
        authRoutes()
        apiRoutes()
    }
}
