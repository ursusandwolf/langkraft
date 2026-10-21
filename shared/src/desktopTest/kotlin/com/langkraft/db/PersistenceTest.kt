package com.langkraft.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistenceTest {
    @Test
    fun updatingContentAndSubtitlePreservesVocabularyReferences() = runTest {
        withDatabase { driver, queries ->
            seed(queries)
            queries.insertContent(id = "content", title = "Updated", audioUrl = "/audio.opus",
                localAudioPath = "/offline", sourceUrl = "source", durationSeconds = 10, createdAt = 2)
            queries.insertSubtitleLine(id = "line", contentId = "content", startMs = 0,
                endMs = 1000, textDe = "Guten Tag", textEn = "Good day")
            assertEquals(1, queries.selectSubtitlesForContent("content").awaitAsList().size)
            driver.executeQuery(null, "SELECT contentId, subtitleLineId FROM Vocabulary", { cursor ->
                cursor.next()
                assertEquals("content", cursor.getString(0))
                assertEquals("line", cursor.getString(1))
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            }, 0)
            assertEquals(1, queries.selectAllContent().awaitAsOne().createdAt)
        }
    }

    @Test
    fun editingWordPreservesReviewScheduleAndAddedAt() = runTest {
        withDatabase { driver, queries ->
            seed(queries)
            driver.execute(null, "UPDATE Vocabulary SET nextReviewAt=100, intervalDays=7, easeFactor=2.7", 0)
            queries.upsertWord(id = "word", word = "Tag", lemma = "Tag", translation = "day",
                contextSentence = "Guten Tag", contentId = "content", subtitleLineId = "line",
                addedAt = 2, status = "LEARNING")
            val saved = queries.selectVocabularyToReview(100).awaitAsOne()
            assertEquals(100, saved.nextReviewAt)
            assertEquals(7, saved.intervalDays)
            assertEquals(2.7, saved.easeFactor)
            assertEquals(1, saved.addedAt)
            assertEquals("LEARNING", saved.status)
        }
    }

    private suspend fun seed(queries: AppDatabaseQueries) {
        queries.insertContent(id = "content", title = "Title", audioUrl = "/audio.opus",
            localAudioPath = null, sourceUrl = "source", durationSeconds = 10, createdAt = 1)
        queries.insertSubtitleLine(id = "line", contentId = "content", startMs = 0,
            endMs = 1000, textDe = "Guten Tag", textEn = null)
        queries.upsertWord(id = "word", word = "Tag", lemma = null, translation = null,
            contextSentence = "Guten Tag", contentId = "content", subtitleLineId = "line",
            addedAt = 1, status = "NEW")
    }

    private suspend fun withDatabase(block: suspend (JdbcSqliteDriver, AppDatabaseQueries) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            AppDatabase.Schema.create(driver).await()
            block(driver, AppDatabase(driver).appDatabaseQueries)
        }
    }
}
