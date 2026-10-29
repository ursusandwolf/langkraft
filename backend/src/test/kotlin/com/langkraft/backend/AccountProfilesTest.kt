package com.langkraft.backend

import app.cash.sqldelight.async.coroutines.*
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.profile.JvmLocalProfileStore
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.data.sync.SyncManager
import com.langkraft.data.sync.UserSession
import com.langkraft.domain.model.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.http.*
import org.koin.ktor.ext.get
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import com.langkraft.backend.db.Users
import com.langkraft.backend.db.VocabularySync
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AccountProfilesTest {
    @TempDir lateinit var directory: Path

    @Test fun guestBindingThenAtoBtoASeparatesAccountsWordsQueuesAndCursors() = testApplication {
        lateinit var database: Database
        environment { config = MapApplicationConfig(
            "jwt.secret" to "profile-test-secret-at-least-thirty-two-characters", "jwt.issuer" to "profiles", "jwt.audience" to "profiles",
            "jwt.realm" to "test", "jwt.validity_ms" to "86400000", "ai.provider" to "none",
            "storage.data_dir" to directory.resolve("backend").toString(), "storage.downloads_dir" to directory.resolve("downloads").toString(),
            "ingestion.cleanup_interval_ms" to "3600000", "ingestion.job_expiry_ms" to "86400000", "ingestion.worker_count" to "1"
        ) }
        application { module(); database = get() }
        val store = JvmLocalProfileStore(directory.resolve("client").toFile())
        val profileA = store.create("Alice")
        val profileB = store.create("Bob")
        val first = createJvmDatabase(store.directory(profileA.id).resolve("langkraft.db"))
        val second = createJvmDatabase(store.directory(profileB.id).resolve("langkraft.db"))
        lateinit var alice: UserSession
        lateinit var bob: UserSession
        val httpA = createClient { install(ContentNegotiation) { json() }; defaultRequest { alice.token.value?.let { bearerAuth(it) } } }
        val httpB = createClient { install(ContentNegotiation) { json() }; defaultRequest { bob.token.value?.let { bearerAuth(it) } } }
        val auth = createClient { install(ContentNegotiation) { json() } }
        try {
            val a = SqlDelightVocabularyRepository(first.access, httpA, Url("http://localhost"))
            val b = SqlDelightVocabularyRepository(second.access, httpB, Url("http://localhost"))
            alice = UserSession(first.access, Url("http://localhost")) { httpA }
            bob = UserSession(second.access, Url("http://localhost")) { httpB }
            a.saveWord(VocabularyWord("alice-word", "Haus", contextSentence = "Das Haus"))
            assertEquals(1, alice.guestBindingPreview()?.wordCount)
            assertFailsWith<IllegalStateException> { alice.signIn("alice@example.com", "password", "Alice") }
            alice.signIn("alice@example.com", "password", "Alice", bindGuestWords = true)
            val syncA = SyncManager(a)
            syncA.sync(force = true)
            assertNull(syncA.syncError.value)
            val cursorA = a.getSyncMetadata("last_sync_timestamp")
            assertNotNull(cursorA)
            alice.signOut()
            assertFailsWith<IllegalStateException> { alice.signIn("bob@example.com", "password", "Bob", bindGuestWords = true) }
            store.select(profileB.id)
            bob.signIn("bob@example.com", "password", "Bob")
            assertTrue(second.database.appDatabaseQueries.selectAllVocabulary().awaitAsList().isEmpty())
            assertNull(b.getSyncMetadata("last_sync_timestamp"))
            b.saveWord(VocabularyWord("bob-word", "Tag", contextSentence = "Guten Tag"))
            val syncB = SyncManager(b)
            syncB.sync(force = true)
            assertNull(syncB.syncError.value)
            assertEquals(listOf("bob-word"), second.database.appDatabaseQueries.selectAllVocabulary().awaitAsList().map { it.id })
            assertTrue(second.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().isEmpty())
            store.select(profileA.id)
            alice.signIn("alice@example.com", "password")
            syncA.sync(force = true)
            assertNull(syncA.syncError.value)
            assertEquals(cursorA, a.getSyncMetadata("last_sync_timestamp"))
            assertEquals(listOf("alice-word"), first.database.appDatabaseQueries.selectAllVocabulary().awaitAsList().map { it.id })
            assertTrue(first.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().isEmpty())
            val oldToken = requireNotNull(alice.token.value)
            val originalId = JWT.decode(oldToken).subject
            assertNotNull(originalId)
            val legacyToken = JWT.create().withAudience("profiles").withIssuer("profiles").withClaim("email", "alice@example.com")
                .sign(Algorithm.HMAC256("profile-test-secret-at-least-thirty-two-characters"))
            assertEquals(HttpStatusCode.Unauthorized, auth.post("/api/sync") {
                bearerAuth(legacyToken); contentType(ContentType.Application.Json); setBody(SyncRequest(0,emptyList()))
            }.status)
            transaction(database) {
                VocabularySync.deleteWhere { VocabularySync.userId eq originalId }
                Users.deleteWhere { Users.id eq originalId }
            }
            val recreated = auth.post("/api/auth/register") {
                contentType(ContentType.Application.Json); setBody(RegisterRequest("alice@example.com", "password", "New Alice"))
            }.body<AuthResponse>()
            assertNotEquals(originalId, recreated.user.id)
            assertEquals(HttpStatusCode.Unauthorized, auth.post("/api/sync") {
                bearerAuth(oldToken); contentType(ContentType.Application.Json); setBody(SyncRequest(0,emptyList()))
            }.status)
            alice.signOut()
            assertFailsWith<IllegalStateException> { alice.signIn("alice@example.com", "password") }
            assertEquals("Haus", first.database.appDatabaseQueries.selectAllVocabulary().awaitAsList().single().word)
        } finally { auth.close(); httpA.close(); httpB.close(); first.close(); second.close() }
    }
}
