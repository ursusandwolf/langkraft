package com.langkraft.backend.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

class BackendDatabaseTest {
    @TempDir lateinit var directory: Path
    private val file get() = directory.resolve("backend.db").toFile()

    private fun <T> query(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use(block)

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.scalar(sql: String): String = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
    private fun backups() = directory.toFile().listFiles()!!.filter { ".before-backend-schema-" in it.name }

    @Test fun freshDatabaseAndRestartUseTheSameVersionWithoutBackup() {
        val db = BackendDatabase.open(file)
        // SQL remains compatible with the production Exposed declarations.
        transaction(db) { assertTrue(SchemaUtils.createStatements(Users, VocabularySync, IngestionJobs).isEmpty()) }
        query { connection ->
            assertEquals("3", connection.scalar("SELECT COUNT(*) FROM backend_schema_history"))
            connection.execute("INSERT INTO users VALUES ('a', 'a@example.org', 'hash', 'A')")
        }
        BackendDatabase.open(file)
        query { assertEquals("1", it.scalar("SELECT COUNT(*) FROM users")) }
        assertTrue(backups().isEmpty())
    }

    @Test fun oldestFixtureRetainsOwnerSrsAndGetsOneTimeCursor() {
        fixture("backend_v1.sql")
        BackendDatabase.open(file)
        val cursor = query { connection ->
            assertEquals("hash", connection.scalar("SELECT password_hash FROM users"))
            assertEquals("a", connection.scalar("SELECT user_id FROM vocabulary_sync"))
            assertEquals("8", connection.scalar("SELECT interval_days FROM vocabulary_sync"))
            assertEquals("2.7", connection.scalar("SELECT ease_factor FROM vocabulary_sync"))
            assertEquals("0", connection.scalar("SELECT lapse_count FROM vocabulary_sync"))
            assertEquals("[]", connection.scalar("SELECT tags FROM vocabulary_sync"))
            connection.scalar("SELECT server_updated FROM vocabulary_sync").also { assertTrue(it.toLong() > 0) }
        }
        val backup = backups().single()
        DriverManager.getConnection("jdbc:sqlite:${backup.absolutePath}").use { connection ->
            assertEquals("Haus", connection.scalar("SELECT word FROM vocabulary_sync"))
            assertEquals("0", connection.scalar("SELECT COUNT(*) FROM pragma_table_info('vocabulary_sync') WHERE name = 'server_updated'"))
        }
        BackendDatabase.open(file)
        query { assertEquals(cursor, it.scalar("SELECT server_updated FROM vocabulary_sync")) }
        assertEquals(1, backups().size)
    }

    @Test fun preSyncFixturePreservesTagsJobsAndSrs() {
        fixture("backend_v1.sql")
        query { connection ->
            connection.execute("ALTER TABLE vocabulary_sync ADD COLUMN lapse_count INT NOT NULL DEFAULT 4")
            connection.execute("ALTER TABLE vocabulary_sync ADD COLUMN tags TEXT NOT NULL DEFAULT '[\"noun\"]'")
            migration(connection, 2, "srs_and_jobs", skipAlter = true)
            connection.execute("INSERT INTO ingestion_jobs VALUES ('job', 'READY', 'https://example.org', '{\"id\":\"content\"}', NULL, 1, 2)")
        }
        BackendDatabase.open(file)
        query { connection ->
            assertEquals("4", connection.scalar("SELECT lapse_count FROM vocabulary_sync"))
            assertEquals("[\"noun\"]", connection.scalar("SELECT tags FROM vocabulary_sync"))
            assertEquals("{\"id\":\"content\"}", connection.scalar("SELECT content_json FROM ingestion_jobs"))
        }
        assertEquals(1, backups().size)
    }

    @Test fun currentUnversionedExposedSchemaPreservesTombstonesAndCursor() {
        val db = org.jetbrains.exposed.sql.Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(Users, VocabularySync, IngestionJobs) }
        query { connection ->
            connection.execute("INSERT INTO users VALUES ('a', 'a@example.org', 'hash', 'A')")
            connection.execute("""INSERT INTO vocabulary_sync
                (id, user_id, word, context_sentence, added_at, status, next_review_at, interval_days,
                 ease_factor, lapse_count, tags, last_updated, server_updated, deleted)
                VALUES ('w', 'a', 'Haus', 'Das Haus', 1, 'LEARNING', 123, 8, 2.7, 4, '[]', 9, 9999999999999, 1)""")
            connection.execute("""INSERT INTO vocabulary_sync
                (id, user_id, word, context_sentence, added_at, status, next_review_at, interval_days,
                 ease_factor, lapse_count, tags, last_updated)
                VALUES ('zero', 'a', 'Buch', 'Das Buch', 1, 'NEW', 0, 0, 2.5, 0, '[]', 9)""")
        }
        BackendDatabase.open(file)
        query { connection ->
            assertEquals("9999999999999", connection.scalar("SELECT server_updated FROM vocabulary_sync WHERE id = 'w'"))
            assertEquals("1", connection.scalar("SELECT deleted FROM vocabulary_sync WHERE id = 'w'"))
            assertTrue(connection.scalar("SELECT server_updated FROM vocabulary_sync WHERE id = 'zero'").toLong() > 9999999999999L)
        }
    }

    @Test fun unsupportedSchemaFailsBeforeBackupOrMutation() {
        fixture("backend_v1.sql")
        query { it.execute("ALTER TABLE users ADD COLUMN unexpected TEXT") }
        assertFailsWith<IllegalStateException> { BackendDatabase.open(file) }
        query { connection ->
            assertEquals("0", connection.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name = 'backend_schema_history'"))
            assertEquals("Haus", connection.scalar("SELECT word FROM vocabulary_sync"))
        }
        assertTrue(backups().isEmpty())
    }

    @Test fun futureVersionAndModifiedHistoryAreRejected() {
        BackendDatabase.open(file)
        query { it.execute("UPDATE backend_schema_history SET checksum = 'changed' WHERE version = 1") }
        assertFailsWith<IllegalStateException> { BackendDatabase.open(file) }
        query { it.execute("UPDATE backend_schema_history SET version = 99 WHERE version = 1") }
        assertFailsWith<IllegalStateException> { BackendDatabase.open(file) }
        assertTrue(backups().isEmpty())
    }

    @Test fun failedMigrationRollsBackDdlDataAndHistoryAndCanRetry() {
        fixture("backend_v1.sql")
        query { connection ->
            connection.execute("CREATE TRIGGER reject_cursor BEFORE UPDATE ON vocabulary_sync BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        }
        assertFailsWith<java.sql.SQLException> { BackendDatabase.open(file) }
        query { connection ->
            assertEquals("0", connection.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name = 'backend_schema_history'"))
            assertEquals("0", connection.scalar("SELECT COUNT(*) FROM pragma_table_info('vocabulary_sync') WHERE name = 'lapse_count'"))
            assertEquals("Haus", connection.scalar("SELECT word FROM vocabulary_sync"))
            connection.execute("DROP TRIGGER reject_cursor")
        }
        BackendDatabase.open(file)
        assertEquals(2, backups().size)
        query { assertEquals("3", it.scalar("SELECT MAX(version) FROM backend_schema_history")) }
    }

    @Test fun storedVersionTwoCanUpgradeAndRestart() {
        BackendDatabase.open(file)
        query { connection ->
            connection.execute("ALTER TABLE vocabulary_sync DROP COLUMN server_updated")
            connection.execute("ALTER TABLE vocabulary_sync DROP COLUMN deleted")
            connection.execute("DELETE FROM backend_schema_history WHERE version = 3")
        }
        BackendDatabase.open(file)
        BackendDatabase.open(file)
        assertEquals(1, backups().size)
        query { assertEquals("3", it.scalar("SELECT MAX(version) FROM backend_schema_history")) }
    }

    @Test fun orphanedOwnerFailsBeforeMigration() {
        fixture("backend_v1.sql")
        query { it.execute("DELETE FROM users") }
        assertFailsWith<IllegalStateException> { BackendDatabase.open(file) }
        assertTrue(backups().isEmpty())
    }

    @Test fun backupIncludesCommittedWalAndQuotedPaths() {
        val quotedFile = directory.resolve("user's backend.db").toFile()
        fixture("backend_v1.sql")
        file.renameTo(quotedFile).also { assertTrue(it) }
        DriverManager.getConnection("jdbc:sqlite:${quotedFile.absolutePath}").use { writer ->
            writer.execute("PRAGMA journal_mode = WAL")
            writer.execute("UPDATE vocabulary_sync SET word = 'WAL'")
            BackendDatabase.open(quotedFile)
            val backup = backups().single()
            DriverManager.getConnection("jdbc:sqlite:${backup.absolutePath}").use {
                assertEquals("WAL", it.scalar("SELECT word FROM vocabulary_sync"))
                assertEquals("0", it.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name = 'backend_schema_history'"))
            }
        }
    }

    @Test fun completedMigrationDoesNotRewriteZeroCursorsOnRestart() {
        BackendDatabase.open(file)
        query { connection ->
            connection.execute("INSERT INTO users VALUES ('a', 'a@example.org', 'hash', 'A')")
            connection.execute("""INSERT INTO vocabulary_sync
                (id, user_id, word, context_sentence, added_at, status, next_review_at, interval_days,
                 ease_factor, lapse_count, tags, last_updated)
                VALUES ('w', 'a', 'Haus', 'Das Haus', 1, 'NEW', 0, 0, 2.5, 0, '[]', 9)""")
        }
        BackendDatabase.open(file)
        query { assertEquals("0", it.scalar("SELECT server_updated FROM vocabulary_sync")) }
        assertTrue(backups().isEmpty())
    }

    @Test fun activeMigrationLockBlocksASecondBootstrap() {
        java.nio.channels.FileChannel.open(directory.resolve("backend.db.migration.lock"),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertFailsWith<java.nio.channels.OverlappingFileLockException> { BackendDatabase.open(file) }
                assertFalse(file.exists())
            }
        }
        BackendDatabase.open(file)
    }

    private fun fixture(name: String) = query { connection ->
        checkNotNull(javaClass.getResource("/db/fixtures/$name")).readText()
            .split(';').map(String::trim).filter(String::isNotEmpty).forEach { connection.execute(it) }
    }

    private fun migration(connection: Connection, version: Int, name: String, skipAlter: Boolean) {
        checkNotNull(javaClass.getResource("/db/migrations/V${version}__$name.sql")).readText()
            .split(';').map(String::trim).filter { it.isNotEmpty() && (!skipAlter || !it.startsWith("ALTER")) }
            .forEach { connection.execute(it) }
    }
}
