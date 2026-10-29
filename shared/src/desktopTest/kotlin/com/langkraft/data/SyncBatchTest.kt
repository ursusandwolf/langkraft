package com.langkraft.data

import app.cash.sqldelight.async.coroutines.*
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class SyncBatchTest {
    @Test fun countBoundedBatchesRetainConflictsAndNewerOfflineRevisions() = runBlocking {
        val directory = Files.createTempDirectory("langkraft-sync-batches-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        lateinit var repository: SqlDelightVocabularyRepository
        lateinit var lateWord: VocabularyWord
        lateinit var editedWord: VocabularyWord
        val requests = mutableListOf<SyncRequest>()
        val client = HttpClient(MockEngine { request ->
            val body = (request.body as TextContent).text
            assertTrue(body.encodeToByteArray().size <= SyncLimits.MAX_BODY_BYTES)
            val sent = Json.decodeFromString<SyncRequest>(body)
            requests += sent
            if (requests.size == 1) repository.saveWord(editedWord.copy(translation = "new offline edit"))
            // A conflict for a not-yet-sent word must be replayed in the later batch.
            val changes = if (sent.lastSyncTimestamp < 100) listOf(SyncEntry(lateWord.copy(translation = "server winner"), "UPSERT")) else emptyList()
            respond(Json.encodeToString(SyncResponse(100, changes)), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            repository = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            val words = (0..SyncLimits.MAX_BATCH_SIZE).associate { index ->
                val word = VocabularyWord("word-$index", "Haus", contextSentence = "Das Haus")
                repository.saveWord(word)
                word.id to word
            }
            val pending = handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList()
            editedWord = words.getValue(pending.first().wordId)
            lateWord = words.getValue(pending.last().wordId).copy(lastUpdated = pending.last().timestamp + 10_000)
            assertEquals(100L, repository.sync(77))
            assertEquals(listOf(500, 1), requests.map { it.clientChanges.size })
            assertTrue(requests.all { it.lastSyncTimestamp == 77L })
            assertEquals("server winner", handle.database.appDatabaseQueries.selectWordById(lateWord.id).awaitAsOne().translation)
            assertEquals("new offline edit", handle.database.appDatabaseQueries.selectWordById(editedWord.id).awaitAsOne().translation)
            assertEquals(listOf(editedWord.id), handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().map { it.wordId })
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }

    @Test fun byteBoundedBatchesCountUtf8AndPreserveEveryEntry() = runBlocking {
        val directory = Files.createTempDirectory("langkraft-sync-byte-batches-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        val received = mutableListOf<String>()
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            val body = (request.body as TextContent).text
            assertTrue(body.encodeToByteArray().size <= SyncLimits.MAX_BODY_BYTES)
            val sent = Json.decodeFromString<SyncRequest>(body)
            received += sent.clientChanges.map { it.word.id }
            respond(Json.encodeToString(SyncResponse(100, emptyList())), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val repository = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            repeat(45) { index ->
                repository.saveWord(VocabularyWord("word-$index", "Haus", contextSentence = "я".repeat(12_000)))
            }
            // The server can correct a cursor from an older build that is ahead of its history.
            assertEquals(100L, repository.sync(1000))
            assertEquals(2, calls, "Byte budget must split even fewer than 500 entries")
            assertEquals((0..44).map { "word-$it" }.toSet(), received.toSet())
            assertEquals(45, received.size)
            assertTrue(handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().isEmpty())
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }

    @Test fun failedLaterBatchLeavesUnacknowledgedChangesForRetry() = runBlocking {
        val directory = Files.createTempDirectory("langkraft-sync-retry-batches-").toFile()
        val handle = createJvmDatabase(directory.resolve("app.db"))
        var calls = 0
        val sizes = mutableListOf<Int>()
        val client = HttpClient(MockEngine { request ->
            calls++
            sizes += Json.decodeFromString<SyncRequest>((request.body as TextContent).text).clientChanges.size
            if (calls == 2) respond("Try again", HttpStatusCode.ServiceUnavailable)
            else respond(Json.encodeToString(SyncResponse(100, emptyList())), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val repository = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            repeat(SyncLimits.MAX_BATCH_SIZE + 1) { index ->
                repository.saveWord(VocabularyWord("word-$index", "Haus", contextSentence = "Das Haus"))
            }
            assertFailsWith<IllegalStateException> { repository.sync(0) }
            assertEquals(1, handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().size)
            assertEquals(100L, repository.sync(0))
            assertEquals(listOf(500, 1, 1), sizes)
            assertTrue(handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().isEmpty())
        } finally { client.close(); handle.close(); check(directory.deleteRecursively()) }
    }
}
