package com.langkraft.data.sync

import com.langkraft.data.database.DatabaseAccess
import com.langkraft.domain.model.*
import com.langkraft.io.ioDispatcher
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserSession(private val access: DatabaseAccess, private val baseUrl: Url, private val client: () -> HttpClient) {
    private val current = MutableStateFlow<String?>(null)
    val token = current.asStateFlow()
    suspend fun restore() {
        current.value = access.run(ioDispatcher) { appDatabaseQueries.getSyncMetadata("auth_token").awaitAsOneOrNull()?.takeIf { it.isNotBlank() } }
    }
    suspend fun signIn(email: String, password: String, displayName: String? = null) {
        val result = client().post(URLBuilder(baseUrl).apply { appendPathSegments("api", "auth", if (displayName == null) "login" else "register") }.build()) {
            contentType(ContentType.Application.Json)
            if (displayName == null) setBody(AuthRequest(email, password))
            else setBody(RegisterRequest(email, password, displayName))
        }
        check(result.status.isSuccess()) { "Sign in failed: HTTP ${result.status.value}" }
        val response = result.body<AuthResponse>()
        access.run(ioDispatcher) { appDatabaseQueries.setSyncMetadata("auth_token", response.token) }
        current.value = response.token
    }
    suspend fun signOut() {
        access.run(ioDispatcher) { appDatabaseQueries.setSyncMetadata("auth_token", "") }
        current.value = null
    }
}
