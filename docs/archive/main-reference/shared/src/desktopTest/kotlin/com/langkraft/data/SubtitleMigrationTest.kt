package com.langkraft.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.data.audio.FileAudioStorage
import com.langkraft.data.database.DatabaseHandle
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.remote.ContentApi
import com.langkraft.data.repository.SqlDelightContentRepository
import com.langkraft.db.AppDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleMigrationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun versionOneMigratesWithoutLosingContentWordLinksOrSrs() = runTest {
        val file = folder.newFile("v1.db")
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            val oldSchema = javaClass.getResource("/database-v1.sql")!!.readText()
            oldSchema.split(';').filter { it.isNotBlank() }.forEach { driver.execute(null, it, 0) }
            driver.execute(null, "INSERT INTO Content VALUES ('content','Title','audio','/offline','source',10,1)", 0)
            driver.execute(null, "INSERT INTO SubtitleLine VALUES ('z-first','content',0,1000,'First',NULL)", 0)
            driver.execute(null, "INSERT INTO SubtitleLine VALUES ('a-second','content',0,1000,'Second',NULL)", 0)
            driver.execute(null, """INSERT INTO Vocabulary
                (id,word,contentId,subtitleLineId,addedAt,nextReviewAt,intervalDays,easeFactor,status)
                VALUES ('word','First','content','z-first',1,100,7,2.7,'LEARNING')""", 0)
            driver.execute(null, "PRAGMA user_version=1", 0)
        }
        val client = HttpClient(MockEngine { error("No network expected") })
        fun repository(handle: DatabaseHandle) = SqlDelightContentRepository(handle.access,
            ContentApi(client, "https://backend"), FileAudioStorage(folder.root), { 0L })
        try {
            val handle = createJvmDatabase(file)
            try {
                assertEquals(2L, AppDatabase.Schema.version)
                val repository = repository(handle)
                val content = repository.getContentById("content")!!
                // Legacy ties have no original ordinal; use the same deterministic fallback in both queries.
                assertEquals(listOf("a-second", "z-first"), content.subtitles.map { it.id })
                assertEquals(content, repository.getAllContent().first().single())
                assertEquals("/offline", content.localAudioPath)
                val word = handle.database.appDatabaseQueries.selectVocabularyToReview(100).awaitAsOne()
                assertEquals("content", word.contentId)
                assertEquals("z-first", word.subtitleLineId)
                assertEquals(100L, word.nextReviewAt)
                assertEquals(7L, word.intervalDays)
                assertEquals(2.7, word.easeFactor)
                assertEquals("LEARNING", word.status)
                repository.saveContent(content.copy(subtitles = content.subtitles.reversed()))
            } finally { handle.close() }
            val reopened = createJvmDatabase(file)
            try {
                val repository = repository(reopened)
                val content = repository.getContentById("content")!!
                assertEquals(listOf("z-first", "a-second"), content.subtitles.map { it.id })
                assertEquals(content, repository.getAllContent().first().single())
            } finally { reopened.close() }
        } finally { client.close() }
    }
}
