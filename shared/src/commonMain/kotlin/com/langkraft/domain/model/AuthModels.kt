package com.langkraft.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncEntry(
    val word: VocabularyWord,
    val changeType: String // "UPSERT" or "DELETE"
)

@Serializable
data class SyncRequest(
    val lastSyncTimestamp: Long,
    val clientChanges: List<SyncEntry>
)

@Serializable
data class SyncResponse(
    val serverTimestamp: Long,
    val serverChanges: List<SyncEntry>
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
