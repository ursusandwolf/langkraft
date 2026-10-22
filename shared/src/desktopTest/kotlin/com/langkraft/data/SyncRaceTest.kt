package com.langkraft.data

import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.repository.SqlDelightVocabularyRepository
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
import kotlin.test.*

class SyncRaceTest {
    @Test fun acknowledgementDoesNotDeleteANewerOfflineEdit() = runBlocking {
        val directory = Files.createTempDirectory("langkraft-sync-race-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        lateinit var repository: SqlDelightVocabularyRepository
        val original = VocabularyWord("word", "Haus", contextSentence="Das Haus")
        val client = HttpClient(MockEngine {
            repository.saveWord(original.copy(translation="new offline edit"))
            val payload = SyncResponse(10, listOf(SyncEntry(original.copy(translation="stale server response"), "UPSERT")))
            respond(Json.encodeToString(payload), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            repository = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            repository.saveWord(original)
            repository.sync(0)
            assertEquals("new offline edit", handle.database.appDatabaseQueries.selectWordById("word").awaitAsOne().translation)
            assertEquals(1, handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().size)
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }
}
