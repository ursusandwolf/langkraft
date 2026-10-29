package com.langkraft.data.sync

import com.langkraft.data.database.DatabaseAccess
import com.langkraft.domain.model.*
import com.langkraft.io.ioDispatcher
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.async.coroutines.awaitAsList
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.util.decodeBase64String
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class UserSession(private val access: DatabaseAccess, private val baseUrl: Url, private val client: () -> HttpClient) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<String?>(null)
    val token = current.asStateFlow()
    suspend fun restore() = mutex.withLock {
        access.run(ioDispatcher) {
            val saved = appDatabaseQueries.getSyncMetadata("auth_token").awaitAsOneOrNull()?.takeIf { it.isNotBlank() }
            preserveLegacyOwner(saved)
            current.value = saved
        }
    }
    suspend fun signIn(email: String, password: String, displayName: String? = null) = mutex.withLock {
        // Reject an incompatible profile before registration can create a remote account.
        access.run(ioDispatcher) { transaction { ensureProfileOwner(email) } }
        val result = client().post(URLBuilder(baseUrl).apply { appendPathSegments("api", "auth", if (displayName == null) "login" else "register") }.build()) {
            contentType(ContentType.Application.Json)
            if (displayName == null) setBody(AuthRequest(email, password))
            else setBody(RegisterRequest(email, password, displayName))
        }
        check(result.status.isSuccess()) { "Sign in failed: HTTP ${result.status.value}" }
        val response = result.body<AuthResponse>()
        withContext(NonCancellable) {
            access.run(ioDispatcher) {
                transaction {
                    ensureProfileOwner(response.user.email)
                    val requestedOwner = ownerKey(response.user.email)
                    appDatabaseQueries.setSyncMetadata("auth_profile_owner", requestedOwner)
                    appDatabaseQueries.setSyncMetadata("auth_token", response.token)
                }
                current.value = response.token
            }
        }
    }
    suspend fun signOut() = mutex.withLock {
        withContext(NonCancellable) {
            access.run(ioDispatcher) {
                preserveLegacyOwner(appDatabaseQueries.getSyncMetadata("auth_token").awaitAsOneOrNull())
                appDatabaseQueries.setSyncMetadata("auth_token", "")
                current.value = null
            }
        }
    }

    private fun ownerKey(email: String) = Json.encodeToString(listOf(baseUrl.toString(), email))

    private suspend fun com.langkraft.db.AppDatabase.ensureProfileOwner(email: String) {
        preserveLegacyOwner(appDatabaseQueries.getSyncMetadata("auth_token").awaitAsOneOrNull())
        val owner = appDatabaseQueries.getSyncMetadata("auth_profile_owner").awaitAsOneOrNull()
        check(owner == ownerKey(email) || (owner == null &&
            appDatabaseQueries.countWordsByStatus().awaitAsList().isEmpty() &&
            appDatabaseQueries.getAllPendingChanges().awaitAsList().isEmpty() &&
            appDatabaseQueries.getSyncMetadata("last_sync_timestamp").awaitAsOneOrNull() == null)) {
            "This local profile contains another account's data or has unknown ownership. Use a separate local profile."
        }
    }

    // A legacy claim only identifies the local data owner; server JWT verification still authenticates requests.
    private suspend fun com.langkraft.db.AppDatabase.preserveLegacyOwner(saved: String?) {
        if (saved.isNullOrBlank() || appDatabaseQueries.getSyncMetadata("auth_profile_owner").awaitAsOneOrNull() != null) return
        val email = runCatching {
            val payload = saved.split('.')[1].replace('-', '+').replace('_', '/')
            Json.parseToJsonElement(payload.padEnd((payload.length + 3) / 4 * 4, '=').decodeBase64String())
                .jsonObject["email"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        appDatabaseQueries.setSyncMetadata("auth_profile_owner", ownerKey(email))
    }
}
