package com.langkraft.data

import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.domain.model.VocabularyWord
import app.cash.sqldelight.async.coroutines.awaitAsList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class SyncHttpErrorsTest {
    @Test fun failedSyncRetainsPendingEditsAndHidesTransportAndResponseDetails(): Unit = runBlocking {
        val directory = Files.createTempDirectory("langkraft-sync-errors-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        var status = HttpStatusCode.Unauthorized
        val client = HttpClient(MockEngine { respond("private-server-details", status, headersOf(HttpHeaders.ContentType,"application/json")) }) {
            install(ContentNegotiation) { json() }
        }
        try {
            val repository = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            repository.saveWord(VocabularyWord("word", "Haus", contextSentence="Das Haus"))
            val unauthorized = assertFailsWith<IllegalStateException> { repository.sync(0) }
            assertEquals("Sign in to synchronize this library.", unauthorized.message)
            status = HttpStatusCode.OK
            val invalid = assertFailsWith<IllegalStateException> { repository.sync(0) }
            assertEquals("The server returned an invalid synchronization response.", invalid.message)
            assertFalse(invalid.message.orEmpty().contains("private"))
            assertEquals(1, handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().size)
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }
}
