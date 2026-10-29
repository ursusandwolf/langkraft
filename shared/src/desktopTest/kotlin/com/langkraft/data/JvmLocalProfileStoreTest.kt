package com.langkraft.data

import com.langkraft.data.profile.JvmLocalProfileStore
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.domain.model.*
import app.cash.sqldelight.async.coroutines.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.Url
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class JvmLocalProfileStoreTest {
    @Test fun namedProfilesHaveSeparateWordsQueueCursorAndAudioAndPreserveDefaultLibrary(): Unit = runBlocking {
        val root = Files.createTempDirectory("langkraft-profiles-").toFile()
        val client = HttpClient(MockEngine { error("No network needed") })
        try {
            val store = JvmLocalProfileStore(root)
            assertEquals(ProfileId(""), store.selected())
            assertEquals(root.canonicalFile, store.directory(ProfileId("")).canonicalFile)
            val a = store.create("Alice")
            val b = store.create("Bob")
            val first = createJvmDatabase(store.directory(a.id).resolve("langkraft.db"))
            val second = createJvmDatabase(store.directory(b.id).resolve("langkraft.db"))
            try {
                val repo = SqlDelightVocabularyRepository(first.access, client, Url("http://localhost"))
                repo.saveWord(VocabularyWord("word", "Haus", contextSentence = "Das Haus"))
                repo.setSyncMetadata("last_sync_timestamp", "123")
                val audio = store.directory(a.id).resolve("audio").apply { mkdirs() }.resolve("private.mp3")
                audio.writeText("alice")
                store.select(b.id)
                assertEquals(b.id, JvmLocalProfileStore(root).selected())
                assertTrue(second.database.appDatabaseQueries.selectAllVocabulary().awaitAsList().isEmpty())
                assertTrue(second.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().isEmpty())
                assertNull(second.database.appDatabaseQueries.getSyncMetadata("last_sync_timestamp").awaitAsOneOrNull())
                assertFalse(store.directory(b.id).resolve("audio/private.mp3").exists())
                store.select(a.id)
                assertEquals("Haus", first.database.appDatabaseQueries.selectWordById("word").awaitAsOne().word)
                assertEquals("123", repo.getSyncMetadata("last_sync_timestamp"))
                assertEquals("alice", audio.readText())
            } finally { first.close(); second.close() }
        } finally { client.close(); check(root.deleteRecursively()) }
    }

    @Test fun invalidRegistryOrSelectionDoesNotOverwriteExistingData(): Unit = runBlocking {
        val root = Files.createTempDirectory("langkraft-profile-registry-").toFile()
        try {
            val store = JvmLocalProfileStore(root)
            val profile = store.create("Valid")
            store.select(profile.id)
            assertFailsWith<IllegalArgumentException> { store.select(ProfileId("local-${UUID.randomUUID()}")) }
            assertEquals(profile.id, store.selected())
            assertFailsWith<IllegalArgumentException> { ProfileId("../outside") }
            root.resolve("profiles.json").writeText("broken registry")
            assertFails { store.create("Must not overwrite") }
            assertEquals("broken registry", root.resolve("profiles.json").readText())
        } finally { check(root.deleteRecursively()) }
    }

    @Test fun concurrentStoresDoNotLoseRegistryUpdates(): Unit = runBlocking {
        val root = Files.createTempDirectory("langkraft-profile-race-").toFile()
        try {
            coroutineScope { (1..8).map { i -> async { JvmLocalProfileStore(root).create("Library $i") } }.awaitAll() }
            assertEquals(9, JvmLocalProfileStore(root).list().size)
        } finally { check(root.deleteRecursively()) }
    }
}
