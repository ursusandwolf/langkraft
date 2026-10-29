package com.langkraft.backend.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class BackendUserRepositoryTest {
    @TempDir lateinit var directory: Path

    @Test fun duplicateInsertReturnsConflictWithoutReplacingExistingCredentials() {
        val db = Database.connect("jdbc:sqlite:${directory.resolve("users.db")}", "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(Users) }
        val repository = BackendUserRepository(db)
        val original = repository.createUser("test@example.com", "original-hash", "Original")
        assertNotNull(original)
        assertNull(repository.createUser("test@example.com", "other-hash", "Other"))
        assertEquals(original, repository.findByEmail("test@example.com")?.id)
        assertEquals("original-hash", repository.findByEmail("test@example.com")?.passwordHash)
    }
}
