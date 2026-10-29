package com.langkraft.data

import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.data.sync.UserSession
import com.langkraft.domain.model.*
import app.cash.sqldelight.async.coroutines.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class UserSessionTest {
    @Test fun guestBindingRequiresConsentRequeuesAllWordsAndRejectsUnknownSyncHistory(): Unit = runBlocking {
        val directory = Files.createTempDirectory("langkraft-guest-binding-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond(Json.encodeToString(AuthResponse("token", UserInfo("account-id", "first@example.com", "First"))),
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val repo = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            repo.saveWord(VocabularyWord("word", "Haus", contextSentence = "Das Haus"))
            handle.database.appDatabaseQueries.clearAllPendingChanges()
            val session = UserSession(handle.access, Url("http://localhost")) { client }
            assertEquals(1, session.guestBindingPreview()?.wordCount)
            assertFailsWith<IllegalStateException> { session.signIn("first@example.com", "password") }
            assertEquals(0, calls)
            repo.setSyncMetadata("last_sync_timestamp", "123")
            assertNull(session.guestBindingPreview())
            assertFailsWith<IllegalStateException> { session.signIn("first@example.com", "password", bindGuestWords = true) }
            assertEquals(0, calls)
            handle.database.appDatabaseQueries.deleteSyncMetadata("last_sync_timestamp")
            session.signIn("first@example.com", "password", bindGuestWords = true)
            assertEquals(1, calls)
            assertEquals("token", session.token.value)
            assertEquals("word", handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().single().wordId)
            assertEquals("Haus", handle.database.appDatabaseQueries.selectWordById("word").awaitAsOne().word)
            assertNotNull(handle.database.appDatabaseQueries.getSyncMetadata("auth_account_id").awaitAsOneOrNull())
            assertNull(session.guestBindingPreview())
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }

    @Test fun recreatedServerAccountWithTheSameEmailCannotAdoptAnExistingProfile(): Unit = runBlocking {
        val directory = Files.createTempDirectory("langkraft-account-identity-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        var id = "original"
        val client = HttpClient(MockEngine {
            respond(Json.encodeToString(AuthResponse("token:$id", UserInfo(id, "first@example.com", "First"))),
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val session = UserSession(handle.access, Url("http://localhost")) { client }
            session.signIn("first@example.com", "password")
            session.signOut()
            id = "recreated"
            assertFailsWith<IllegalStateException> { session.signIn("first@example.com", "password") }
            assertNull(session.token.value)
            assertEquals("", handle.database.appDatabaseQueries.getSyncMetadata("auth_token").awaitAsOne())
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }
    @Test fun profileOwnershipSurvivesLogoutAndPreventsAccountMixing() = runBlocking {
        val directory = Files.createTempDirectory("langkraft-session-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        var email = "first@example.com"
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(Json.encodeToString(AuthResponse("token:$email", UserInfo(email, email, "Test"))),
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val session = UserSession(handle.access, Url("http://localhost")) { client }
            session.signIn(email, "password")
            val repo = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            repo.saveWord(VocabularyWord("word", "Haus", contextSentence = "Das Haus"))
            repo.setSyncMetadata("last_sync_timestamp", "123")
            session.signOut()
            email = "second@example.com"
            assertFailsWith<IllegalStateException> { session.signIn(email, "password") }
            assertEquals(1, requests, "Rejected account must not trigger a remote login or registration")
            assertNull(session.token.value)
            assertEquals("", handle.database.appDatabaseQueries.getSyncMetadata("auth_token").awaitAsOne())
            assertEquals("123", repo.getSyncMetadata("last_sync_timestamp"))
            assertEquals("Haus", handle.database.appDatabaseQueries.selectWordById("word").awaitAsOne().word)
            email = "first@example.com"
            session.signIn(email, "password")
            assertEquals("token:$email", session.token.value)
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }

    @Test fun legacyTokenBindsOwnerBeforeLogoutAndUnknownDataCannotBeAdopted() = runBlocking {
        val directory = Files.createTempDirectory("langkraft-legacy-session-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        var email = "second@example.com"
        val client = HttpClient(MockEngine {
            respond(Json.encodeToString(AuthResponse("new-token", UserInfo(email, email, "Test"))),
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val repo = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            repo.saveWord(VocabularyWord("word", "Haus", contextSentence = "Das Haus"))
            val session = UserSession(handle.access, Url("http://localhost")) { client }
            assertFailsWith<IllegalStateException> { session.signIn(email, "password") }
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"first@example.com"}""".toByteArray())
            repo.setSyncMetadata("auth_token", "header.$payload.signature")
            session.restore()
            session.signOut()
            assertFailsWith<IllegalStateException> { session.signIn(email, "password") }
            email = "first@example.com"
            session.signIn(email, "password")
            assertEquals("new-token", session.token.value)
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }
}
