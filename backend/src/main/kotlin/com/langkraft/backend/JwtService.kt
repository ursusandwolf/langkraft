package com.langkraft.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.ApplicationConfig
import java.util.*

class JwtService(config: ApplicationConfig) {
    private val secret = config.property("jwt.secret").getString()
    private val issuer = config.property("jwt.issuer").getString()
    private val audience = config.property("jwt.audience").getString()

    companion object {
        private const val TOKEN_VALIDITY_MS = 3600000 * 24L // 24 hours
    }

    fun generateToken(email: String): String = JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withClaim("email", email)
        .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_VALIDITY_MS))
        .sign(Algorithm.HMAC256(secret))
}
