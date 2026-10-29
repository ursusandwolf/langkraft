package com.langkraft.backend

import at.favre.lib.crypto.bcrypt.BCrypt
import com.langkraft.backend.db.BackendUserRepository
import com.langkraft.domain.model.AuthResponse
import com.langkraft.domain.model.RegisterRequest
import com.langkraft.domain.model.AuthRequest
import com.langkraft.domain.model.UserInfo

class AuthService(
    private val userRepository: BackendUserRepository,
    private val jwtService: JwtService
) {
    fun register(request: RegisterRequest): AuthResponse? {
        validateCredentials(request.email, request.password)
        require(request.displayName.isNotBlank() && request.displayName.length <= 255) { "Invalid display name" }
        if (userRepository.findByEmail(request.email) != null) return null

        val passwordHash = BCrypt.withDefaults().hashToString(12, request.password.toCharArray())
        val userId = userRepository.createUser(request.email, passwordHash, request.displayName) ?: return null
        
        return AuthResponse(
            token = jwtService.generateToken(request.email),
            user = UserInfo(userId, request.email, request.displayName)
        )
    }

    fun login(request: AuthRequest): AuthResponse? {
        validateCredentials(request.email, request.password)
        val user = userRepository.findByEmail(request.email) ?: return null
        
        val result = BCrypt.verifyer().verify(request.password.toCharArray(), user.passwordHash)
        if (!result.verified) return null
        
        return AuthResponse(
            token = jwtService.generateToken(request.email),
            user = UserInfo(user.id, user.email, user.displayName)
        )
    }

    private fun validateCredentials(email: String, password: String) {
        require(email.length <= 255 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email)) { "Invalid email" }
        require(password.isNotBlank() && password.toByteArray(Charsets.UTF_8).size <= 72) { "Invalid password length" }
    }
}
