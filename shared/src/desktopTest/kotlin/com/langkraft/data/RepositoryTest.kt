package com.langkraft.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.remote.ContentApi
import com.langkraft.data.repository.SqlDelightContentRepository
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val content = ImmersionContent("content", "Title", "https://media/audio.opus",
        null, "https://youtu.be/video", 10, listOf(
            SubtitleLine("second", "content", 1000, 2000, "Tag", null),
            SubtitleLine("first", "content", 0, 1000, "Guten", null)
        ))
    private val word = VocabularyWord("word", "Guten", null, "good", "Guten Tag", "content", "first")

    @Test
    fun libraryFlowEmitsCompleteContentAndOrderedSubtitles() = runTest {
        fixture().use { f ->
            val emissions = mutableListOf<List<ImmersionContent>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.contents.getAllContent().collect { emissions += it }
            }
            runCurrent()
            assertEquals(emptyList(), emissions.last())
            f.contents.saveContent(content)
            runCurrent()
            assertEquals(listOf("first", "second"), emissions.last().single().subtitles.map { it.id })
            assertTrue(emissions.filter { it.isNotEmpty() }.all { it.single().subtitles.size == 2 })
            assertEquals(content.copy(subtitles = content.subtitles.sortedBy { it.startMs }),
                f.contents.getContentById(content.id))
            assertNull(f.contents.getContentById("missing"))
        }
    }

    @Test
    fun updatesPreserveVocabularyReferencesAndSrs() = runTest {
        fixture().use { f ->
            f.contents.saveContent(content)
            f.words.saveWord(word)
            f.driver.execute(null,
                "UPDATE Vocabulary SET nextReviewAt=10, intervalDays=7, easeFactor=2.7", 0)
            f.contents.saveContent(content.copy(title = "Updated", localAudioPath = "/offline"))
            f.words.saveWord(word.copy(translation = "better", status = WordStatus.LEARNING))
            val saved = f.db.appDatabaseQueries.selectVocabularyToReview(10).awaitAsOne()
            assertEquals("content", saved.contentId)
            assertEquals("first", saved.subtitleLineId)
            assertEquals(10L, saved.nextReviewAt)
            assertEquals(7L, saved.intervalDays)
            assertEquals(2.7, saved.easeFactor)
            assertEquals("LEARNING", saved.status)
        }
    }

    @Test
    fun removedSubtitlesClearOnlyTheirWordReferences() = runTest {
        fixture().use { f ->
            f.contents.saveContent(content)
            f.words.saveWord(word)
            f.contents.saveContent(content.copy(subtitles = content.subtitles.filter { it.id == "second" }))
            val saved = f.db.appDatabaseQueries.selectVocabularyToReview(100).awaitAsOne()
            assertEquals("content", saved.contentId)
            assertNull(saved.subtitleLineId)
            assertEquals(listOf("second"), f.contents.getContentById("content")!!.subtitles.map { it.id })
            f.contents.saveContent(content.copy(subtitles = emptyList()))
            assertTrue(f.contents.getContentById("content")!!.subtitles.isEmpty())
        }
    }

    @Test
    fun subtitleFailureRollsBackTheEntireContentSave() = runTest {
        fixture().use { f ->
            f.driver.execute(null, """CREATE TRIGGER reject_subtitle BEFORE INSERT ON SubtitleLine
                WHEN NEW.textDe = 'bad' BEGIN SELECT RAISE(ABORT, 'Invalid subtitle'); END""", 0)
            assertFailsWith<Exception> {
                f.contents.saveContent(content.copy(subtitles = content.subtitles.map { it.copy(textDe = "bad") }))
            }
            assertNull(f.contents.getContentById("content"))
        }
    }

    @Test
    fun conflictingSubtitleIdCannotMoveToAnotherContent() = runTest {
        fixture().use { f ->
            f.contents.saveContent(content)
            assertFailsWith<IllegalArgumentException> {
                f.contents.saveContent(content.copy(id = "other",
                    subtitles = content.subtitles.map { it.copy(contentId = "other") }))
            }
            assertNull(f.contents.getContentById("other"))
            assertEquals(2, f.contents.getContentById("content")!!.subtitles.size)
        }
    }

    @Test
    fun dueWordsRefreshAsTimePassesAndReactToDeletion() = runTest {
        fixture().use { f ->
            f.contents.saveContent(content)
            f.words.saveWord(word)
            f.driver.execute(null, "UPDATE Vocabulary SET nextReviewAt=1000", 0)
            val emissions = mutableListOf<List<VocabularyWord>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.words.getWordsToReview().collect { emissions += it }
            }
            runCurrent()
            assertTrue(emissions.last().isEmpty())
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(listOf(word), emissions.last())
            f.words.deleteWord(word.id)
            runCurrent()
            assertTrue(emissions.last().isEmpty())
            f.words.deleteWord("missing")
        }
    }

    @Test
    fun foreignKeyViolationDoesNotSaveTheWord() = runTest {
        fixture().use { f ->
            assertFailsWith<Exception> { f.words.saveWord(word) }
            assertTrue(f.db.appDatabaseQueries.selectVocabularyToReview(100).awaitAsList().isEmpty())
        }
    }

    @Test
    fun fileDatabaseRetainsContentAndWordsAfterReopening() = runTest {
        val url = "jdbc:sqlite:${folder.newFile("library.db").absolutePath}"
        fixture(url).use { f ->
            f.contents.saveContent(content.copy(localAudioPath = "/offline/audio.opus"))
            f.words.saveWord(word)
        }
        fixture(url, create = false).use { f ->
            assertEquals("/offline/audio.opus", f.contents.getContentById("content")!!.localAudioPath)
            assertEquals(2, f.contents.getContentById("content")!!.subtitles.size)
            val saved = f.db.appDatabaseQueries.selectVocabularyToReview(100).awaitAsOne()
            assertEquals("good", saved.translation)
            assertEquals("first", saved.subtitleLineId)
            assertFalse(saved.id.isBlank())
        }
    }

    private suspend fun TestScope.fixture(
        url: String = JdbcSqliteDriver.IN_MEMORY,
        create: Boolean = true
    ): Fixture {
        val driver = JdbcSqliteDriver(url)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        if (create) AppDatabase.Schema.create(driver).await()
        val database = AppDatabase(driver)
        val access = DatabaseAccess(database)
        val client = HttpClient(MockEngine { error("Unexpected HTTP call") })
        val storage = object : AudioStorage {
            override suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?) =
                error("Unexpected audio download")
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return Fixture(driver, database, client,
            SqlDelightContentRepository(access, ContentApi(client, "https://backend"), storage,
                { testScheduler.currentTime }, dispatcher),
            SqlDelightVocabularyRepository(access, { testScheduler.currentTime }, dispatcher, 1000))
    }

    private class Fixture(
        val driver: JdbcSqliteDriver,
        val db: AppDatabase,
        val client: HttpClient,
        val contents: SqlDelightContentRepository,
        val words: SqlDelightVocabularyRepository
    ) : AutoCloseable {
        override fun close() { client.close(); driver.close() }
    }
}
