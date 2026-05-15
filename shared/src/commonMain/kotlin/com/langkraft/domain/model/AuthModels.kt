package com.langkraft.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncRequest(
    val lastSyncTimestamp: Long,
    val clientChanges: List<VocabularyWord>
)

@Serializable
data class SyncResponse(
    val serverTimestamp: Long,
    val serverChanges: List<VocabularyWord>
)

@Serializable
data class AuthRequest(
    val email: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserInfo
)

@Serializable
data class UserInfo(
    val id: String,
    val email: String,
    val displayName: String
)
