package com.langkraft.backend.db

import com.langkraft.domain.model.SyncEntry
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendVocabularyRepositoryTest {

    private lateinit var repository: BackendVocabularyRepository
    private lateinit var dbFile: File
    private lateinit var database: Database

    @BeforeEach
    fun setup() {
        dbFile = Files.createTempFile("langkraft-test", ".db").toFile()
        database = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
        repository = BackendVocabularyRepository(database)
        
        transaction(database) {
            SchemaUtils.create(Users, VocabularySync)
            // Seed a user to satisfy foreign key constraints
            Users.insert {
                it[id] = "user1"
                it[email] = "test@example.com"
                it[passwordHash] = "hash"
                it[displayName] = "Test User"
            }
        }
    }

    @AfterEach
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `test sync - new word from client`() {
        val userId = "user1"
        val word = VocabularyWord(
            id = "w1",
            word = "Haus",
            lemma = "Haus",
            translation = "House",
            contextSentence = "Das Haus ist gross.",
            addedAt = 1000,
            lastUpdated = 1000
        )

        val serverChanges = repository.sync(userId, listOf(SyncEntry(word, "UPSERT")), 0L)
        
        assertTrue(serverChanges.isEmpty(), "Server should not have any changes for new user")
        
        transaction(database) {
            val saved = VocabularySync.selectAll().single()
            assertEquals("w1", saved[VocabularySync.id])
            assertEquals("Haus", saved[VocabularySync.word])
            assertEquals(1000L, saved[VocabularySync.lastUpdated])
        }
    }

    @Test
    fun `test sync - conflict resolution (Last Write Wins)`() {
        val userId = "user1"
        val wordId = "w1"
        
        // 1. Seed server with an older version
        transaction(database) {
            VocabularySync.insert {
                it[id] = wordId
                it[this.userId] = userId
                it[word] = "Haus"
                it[contextSentence] = "Seed context"
                it[lastUpdated] = 500L
                it[addedAt] = 100L
                it[status] = "NEW"
                it[nextReviewAt] = 0L
                it[intervalDays] = 0
                it[easeFactor] = 2.5
                it[lapseCount] = 0
                it[tags] = ""
            }
        }

        // 2. Client sends a newer version
        val newerWord = VocabularyWord(
            id = wordId,
            word = "Haus (updated)",
            contextSentence = "Updated context",
            addedAt = 100L,
            lastUpdated = 1000L
        )

        repository.sync(userId, listOf(SyncEntry(newerWord, "UPSERT")), 0L)

        // 3. Verify server was updated
        transaction(database) {
            val updated = VocabularySync.select { VocabularySync.id eq wordId }.single()
            assertEquals("Haus (updated)", updated[VocabularySync.word])
            assertEquals(1000L, updated[VocabularySync.lastUpdated])
        }

        // 4. Client sends an older version (should be ignored)
        val olderWord = VocabularyWord(
            id = wordId,
            word = "Haus (stale)",
            contextSentence = "Stale context",
            addedAt = 100L,
            lastUpdated = 700L
        )

        repository.sync(userId, listOf(SyncEntry(olderWord, "UPSERT")), 1000L)

        transaction(database) {
            val final = VocabularySync.select { VocabularySync.id eq wordId }.single()
            assertEquals("Haus (updated)", final[VocabularySync.word], "Server should NOT be downgraded")
            assertEquals(1000L, final[VocabularySync.lastUpdated])
        }
    }

    @Test
    fun `test sync - batch update (N plus 1 check)`() {
        val userId = "user1"
        
        // Seed 50 words
        transaction(database) {
            (1..50).forEach { i ->
                VocabularySync.insert {
                    it[id] = "w$i"
                    it[this.userId] = userId
                    it[word] = "Word $i"
                    it[contextSentence] = "Context $i"
                    it[lastUpdated] = 100L
                    it[addedAt] = 10L
                    it[status] = "NEW"
                    it[nextReviewAt] = 0L
                    it[intervalDays] = 0
                    it[easeFactor] = 2.5
                    it[lapseCount] = 0
                    it[tags] = ""
                }
            }
        }

        // Client updates all 50 words
        val clientChanges = (1..50).map { i ->
            SyncEntry(
                VocabularyWord(
                    id = "w$i",
                    word = "Word $i updated",
                    contextSentence = "Context $i",
                    addedAt = 10L,
                    lastUpdated = 200L
                ), "UPSERT"
            )
        }

        repository.sync(userId, clientChanges, 100L)

        transaction(database) {
            val count = VocabularySync.select { VocabularySync.lastUpdated eq 200L }.count()
            assertEquals(50, count, "All 50 words should be updated to version 200")
        }
    }
}
