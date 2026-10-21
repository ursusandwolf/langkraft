package com.langkraft.backend.db

import com.langkraft.domain.model.SyncEntry
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendVocabularyRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: Database
    private lateinit var repository: BackendVocabularyRepository
    private val userId = "user-1"

    @BeforeEach
    fun setup() {
        db = Database.connect("jdbc:sqlite:${tempDir.resolve("vocabulary.db")}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Users, VocabularySync)
            Users.insert {
                it[id] = userId
                it[email] = "test@example.com"
                it[passwordHash] = "hash"
                it[displayName] = "Test User"
            }
        }
        repository = BackendVocabularyRepository(db)
    }

    @Test
    fun `test initial sync - upsert new word`() {
        val word = VocabularyWord(id = "w1", word = "Haus", contextSentence = "Das Haus", lastUpdated = 1000)
        val clientChanges = listOf(SyncEntry(word, "UPSERT"))
        
        val serverChanges = repository.sync(userId, clientChanges, 0)
        
        assertTrue(serverChanges.isEmpty(), "Server should have no other changes")
        
        val secondSync = repository.sync(userId, emptyList(), 0)
        assertEquals(1, secondSync.size)
        assertEquals("Haus", secondSync.first().word.word)
    }

    @Test
    fun `test conflict resolution - last write wins`() {
        val wordV1 = VocabularyWord(id = "w1", word = "Haus", contextSentence = "Das Haus", lastUpdated = 1000)
        repository.sync(userId, listOf(SyncEntry(wordV1, "UPSERT")), 0)
        
        val wordOld = wordV1.copy(word = "OLD", lastUpdated = 500)
        repository.sync(userId, listOf(SyncEntry(wordOld, "UPSERT")), 0)
        
        val syncResult = repository.sync(userId, emptyList(), 0)
        assertEquals("Haus", syncResult.first().word.word, "Should keep the newer version")
        
        val wordNew = wordV1.copy(word = "NEU", lastUpdated = 2000)
        repository.sync(userId, listOf(SyncEntry(wordNew, "UPSERT")), 0)
        
        val syncResult2 = repository.sync(userId, emptyList(), 0)
        assertEquals("NEU", syncResult2.first().word.word, "Should update to the newer version")
    }

    @Test
    fun `test delete entry`() {
        val word = VocabularyWord(id = "w1", word = "Haus", contextSentence = "Das Haus", lastUpdated = 1000)
        repository.sync(userId, listOf(SyncEntry(word, "UPSERT")), 0)
        
        repository.sync(userId, listOf(SyncEntry(word, "DELETE")), 0)
        
        val syncResult = repository.sync(userId, emptyList(), 0)
        assertTrue(syncResult.isEmpty(), "Word should be deleted")
    }

    @Test
    fun `test server returns only missing changes`() {
        val word1 = VocabularyWord(id = "w1", word = "Word 1", contextSentence = "Ctx 1", lastUpdated = 1000)
        val word2 = VocabularyWord(id = "w2", word = "Word 2", contextSentence = "Ctx 2", lastUpdated = 2000)
        
        repository.sync(userId, listOf(SyncEntry(word1, "UPSERT")), 0)
        
        val word3 = VocabularyWord(id = "w3", word = "Word 3", contextSentence = "Ctx 3", lastUpdated = 3000)
        transaction(db) {
            VocabularySync.insert {
                it[id] = "w3"
                it[VocabularySync.userId] = this@BackendVocabularyRepositoryTest.userId
                it[word] = word3.word
                it[contextSentence] = word3.contextSentence
                it[addedAt] = 0
                it[status] = WordStatus.NEW.name
                it[nextReviewAt] = 0
                it[intervalDays] = 0
                it[easeFactor] = 2.5
                it[lapseCount] = 0
                it[tags] = ""
                it[lastUpdated] = 3000
            }
        }
        
        val result = repository.sync(userId, listOf(SyncEntry(word2, "UPSERT")), 1500)
        
        assertEquals(1, result.size)
        assertEquals("w3", result.first().word.id, "Should return word3 which is newer than 1500 and not in request")
    }
}
