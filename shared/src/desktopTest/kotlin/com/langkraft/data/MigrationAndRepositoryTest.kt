package com.langkraft.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.async.coroutines.*
import com.langkraft.data.database.*
import com.langkraft.data.repository.*
import com.langkraft.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class MigrationAndRepositoryTest {
    private fun content() = ImmersionContent("video", "Title", "/video.mp3", null, "https://youtu.be/video", 2,
        listOf(SubtitleLine("z", "video", 0, 1000, "First", null), SubtitleLine("a", "video", 0, 1000, "Second", null)), listOf(0f, 1f))

    @Test fun migratesBothBranchesWithoutLosingVocabularyOrSyncQueue() = runBlocking {
        val directory = Files.createTempDirectory("langkraft-migration-").toFile()
        try {
            for ((name, version) in listOf("dev" to 0, "dev" to 1, "main" to 1, "main" to 2)) {
                val file = File(directory, "$name-$version.db")
                val old = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                val sql = requireNotNull(javaClass.getResource("/$name-v1.sql")).readText()
                sql.split(';').filter { it.isNotBlank() }.forEach { old.execute(null, it, 0).await() }
                if (name == "main" && version == 2) old.execute(null, "ALTER TABLE SubtitleLine ADD COLUMN ordinal INTEGER NOT NULL DEFAULT 0", 0).await()
                val textColumn = if (name == "main") "textDe" else "originalText"
                old.execute(null, "INSERT INTO Content(id,title,audioUrl,localAudioPath,sourceUrl,durationSeconds,createdAt) VALUES('video','Title','/video.mp3','offline.mp3','https://youtu.be/video',2,100)", 0).await()
                old.execute(null, "INSERT INTO SubtitleLine(id,contentId,startMs,endMs,$textColumn) VALUES('cue','video',0,1000,'Hallo')", 0).await()
                old.execute(null, "INSERT INTO Vocabulary(id,word,contentId,subtitleLineId,addedAt,nextReviewAt,intervalDays,easeFactor,status) VALUES('word','Haus','video','cue',10,5000,7,2.7,'LEARNING')", 0).await()
                old.execute(null, "INSERT INTO Vocabulary(id,word,contentId,subtitleLineId,addedAt) VALUES('remote-word','Fern','remote-video','remote-cue',11)", 0).await()
                if (name == "dev") {
                    old.execute(null, "UPDATE Vocabulary SET lapseCount=3,tags='[\"tag\"]',lastUpdated=42", 0).await()
                    old.execute(null, "INSERT INTO PendingSyncChange VALUES('word','UPSERT',42)", 0).await()
                    old.execute(null, "INSERT INTO SyncMetadata VALUES('last_sync_timestamp','25')", 0).await()
                }
                old.execute(null, "PRAGMA user_version=$version", 0).await()
                old.close()
                val migrated = createJvmDatabase(file)
                try {
                    val q = migrated.database.appDatabaseQueries
                    val word = q.selectWordById("word").awaitAsOne()
                    assertEquals("cue", word.subtitleLineId)
                    assertEquals(7, word.intervalDays)
                    assertEquals(2.7, word.easeFactor)
                    assertEquals(5000, word.nextReviewAt)
                    assertEquals("Hallo", q.selectSubtitlesForContent("video").awaitAsOne().originalText)
                    assertNull(q.selectWordById("remote-word").awaitAsOne().contentId)
                    assertEquals("[\"remote-video\",\"remote-cue\"]", q.getSyncMetadata("source_refs:remote-word").awaitAsOne())
                    assertEquals("COMPLETED", q.selectContentById("video").awaitAsOne().downloadStatus)
                    if (name == "dev") {
                        assertEquals(3, word.lapseCount)
                        assertEquals("[\"tag\"]", word.tags)
                        assertEquals(1, q.getAllPendingChanges().awaitAsList().size)
                        assertEquals("25", q.getSyncMetadata("last_sync_timestamp").awaitAsOne())
                    }
                    assertTrue(File(file.path + ".before-schema-3.bak").isFile)
                } finally { migrated.close() }
                createJvmDatabase(file).close()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun repeatedImportPreservesOfflineAudioSrsLinksAndCueOrder() = runBlocking {
        val directory = Files.createTempDirectory("langkraft-reimport-").toFile()
        val handle = createJvmDatabase(File(directory, "app.db"))
        val client = HttpClient(MockEngine { error("Network is not needed") })
        try {
            val contents = SqlDelightContentRepository(handle.access)
            val words = SqlDelightVocabularyRepository(handle.access, client, Url("http://localhost"))
            contents.saveContent(content().copy(localAudioPath="cached.mp3", downloadStatus=DownloadStatus.COMPLETED))
            words.saveWord(VocabularyWord("word", "Haus", contextSentence="Haus", contentId="video", subtitleLineId="z",
                addedAt=100, intervalDays=7, easeFactor=2.7, lapseCount=3, tags=listOf("tag")))
            contents.saveContent(content().copy(title="New title"))
            val result = requireNotNull(contents.getContentById("video"))
            assertEquals(listOf("z", "a"), result.subtitles.map { it.id })
            assertEquals(listOf(0f, 1f), result.waveform)
            assertEquals("cached.mp3", result.localAudioPath)
            val row = handle.database.appDatabaseQueries.selectWordById("word").awaitAsOne()
            assertEquals("z", row.subtitleLineId)
            assertEquals(7, row.intervalDays)
            assertEquals(3, row.lapseCount)
            assertEquals("[\"tag\"]", row.tags)
            assertEquals(1, handle.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().size)
            assertFailsWith<IllegalArgumentException> { contents.saveContent(content().copy(id="other")) }
            assertEquals("New title", contents.getContentById("video")?.title)
        } finally { client.close(); handle.close(); directory.deleteRecursively() }
    }

    @Test fun rejectsNewerDatabaseWithoutResettingIt(): Unit = runBlocking {
        val directory = Files.createTempDirectory("langkraft-future-").toFile()
        val file = File(directory, "app.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        driver.execute(null, "PRAGMA user_version=999", 0).await()
        driver.close()
        try { assertFailsWith<IllegalArgumentException> { createJvmDatabase(file) } }
        finally { directory.deleteRecursively() }
    }
}
