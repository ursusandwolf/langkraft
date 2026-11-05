package com.langkraft.backend

import at.favre.lib.crypto.bcrypt.BCrypt
import com.langkraft.backend.db.BackendUserRepository
import com.langkraft.domain.model.AuthResponse
import com.langkraft.domain.model.RegisterRequest
import com.langkraft.domain.model.AuthRequest
import com.langkraft.domain.model.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthService(
    private val userRepository: BackendUserRepository,
    private val jwtService: JwtService
) {
    suspend fun register(request: RegisterRequest): AuthResponse? = withContext(Dispatchers.IO) {
        if (userRepository.findByEmail(request.email) != null) return@withContext null

        val passwordHash = BCrypt.withDefaults().hashToString(12, request.password.toCharArray())
        val userId = userRepository.createUser(request.email, passwordHash, request.displayName)

        AuthResponse(
            token = jwtService.generateToken(request.email),
            user = UserInfo(userId, request.email, request.displayName)
        )
    }

    suspend fun login(request: AuthRequest): AuthResponse? = withContext(Dispatchers.IO) {
        val user = userRepository.findByEmail(request.email) ?: return@withContext null

        val result = BCrypt.verifyer().verify(request.password.toCharArray(), user.passwordHash)
        if (!result.verified) return@withContext null

        AuthResponse(
            token = jwtService.generateToken(request.email),
            user = UserInfo(user.id, user.email, user.displayName)
        )
    }
}
