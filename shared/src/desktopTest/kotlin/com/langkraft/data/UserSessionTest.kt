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
