package com.langkraft.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.data.audio.IndexedDbAudioStorage
import com.langkraft.data.database.createWebDatabase
import com.langkraft.data.remote.ContentApi
import com.langkraft.data.repository.SqlDelightContentRepository
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.data.storage.readAudioBlob
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.model.VocabularyWord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebStorageTest {
    @Test
    fun cancelledTransactionRollsBackAndLeavesTheConnectionUsable() = runTest {
        val handle = createWebDatabase()
        val id = "cancelled-${Random.nextInt(1, Int.MAX_VALUE)}"
        val entered = CompletableDeferred<Unit>()
        try {
            val write = launch {
                handle.access.run(Dispatchers.Default) {
                    transaction {
                        appDatabaseQueries.insertContent(id = id, title = "Cancelled", audioUrl = "audio",
                            localAudioPath = null, sourceUrl = "source", durationSeconds = 1, createdAt = 1)
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            entered.await()
            write.cancel()
            write.join()
            handle.access.run(Dispatchers.Default) {
                assertNull(appDatabaseQueries.selectContentById(id).awaitAsOneOrNull())
            }
        } finally { handle.close() }
    }
    @Test
    fun realWorkerRepositoriesRetainContentAndWordWhenReopened() = runTest {
        val id = "browser-${Random.nextInt(1, Int.MAX_VALUE)}"
        val line = SubtitleLine("$id-line", id, 0, 1000, "Guten Tag", null)
        val content = ImmersionContent(id, "Browser test", "https://media/audio.opus",
            null, "https://youtu.be/video", 1, listOf(line))
        val client = HttpClient(MockEngine { error("No HTTP requests expected") })
        val storage = IndexedDbAudioStorage()
        val handle = createWebDatabase()
        try {
            SqlDelightContentRepository(handle.access, ContentApi(client, "https://backend"),
                storage, { 1700000000000L }).saveContent(content)
            SqlDelightVocabularyRepository(handle.access, { 1700000000000L }).saveWord(
                VocabularyWord("$id-word", "Tag", null, "day", line.textDe, id, line.id))
        } finally { handle.close() }
        val reopened = createWebDatabase()
        try {
            val repository = SqlDelightContentRepository(reopened.access, ContentApi(client, "https://backend"),
                storage, { 1700000000000L })
            assertEquals(content, repository.getContentById(id))
            assertEquals(content, repository.getAllContent().first().single { it.id == id })
            val words = withContext(Dispatchers.Default) {
                SqlDelightVocabularyRepository(reopened.access, { 1700000000000L })
                    .getWordsToReview().first()
            }
            assertEquals("day", words.single { it.id == "$id-word" }.translation)
            // A missing parent must fail even after reopening the worker.
            assertFails {
                SqlDelightVocabularyRepository(reopened.access, { 0L }).saveWord(
                    VocabularyWord("$id-bad", "bad", null, null, "context", "$id-missing", null))
            }
            assertEquals(content, repository.getContentById(id))
        } finally { reopened.close(); client.close() }
    }

    @Test
    fun audioCanBeReadAfterCreatingANewStorageInstance() = runTest {
        val location = IndexedDbAudioStorage().store("content",
            ByteReadChannel(byteArrayOf(1, 2, 3, 4)), 4)
        assertTrue(location.startsWith("indexeddb-audio:"))
        assertEquals(4.0, readAudioBlob(location).await().size)
    }

    @Test
    fun playerRestoresSavedAudioAndSeeksAfterMetadataLoads() = runTest {
        // A short PCM WAV keeps this test independent of ffmpeg and codec tooling.
        val data = ByteArray(44 + 8000)
        fun text(offset: Int, value: String) {
            value.encodeToByteArray().copyInto(data, offset)
        }
        fun number(offset: Int, value: Int, count: Int) {
            repeat(count) { data[offset + it] = (value ushr (it * 8)).toByte() }
        }
        text(0, "RIFF"); number(4, data.size - 8, 4); text(8, "WAVE")
        text(12, "fmt "); number(16, 16, 4); number(20, 1, 2); number(22, 1, 2)
        number(24, 8000, 4); number(28, 16000, 4); number(32, 2, 2); number(34, 16, 2)
        text(36, "data"); number(40, data.size - 44, 4)
        val location = IndexedDbAudioStorage().store("seek-test", ByteReadChannel(data), data.size.toLong())
        val player = AudioPlayerImpl()
        try {
            player.load(location)
            player.seekTo(100)
            withContext(Dispatchers.Default) {
                withTimeout(10000) { player.currentTimeMs.first { it >= 100 } }
            }
            assertNull(player.error.value)
        } finally { player.release() }
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun cancelledAudioDownloadDoesNotPublishALocation() = runTest {
        val source = ByteChannel(autoFlush = true)
        var location: String? = null
        val started = CompletableDeferred<Unit>()
        val job = launch {
            started.complete(Unit)
            location = IndexedDbAudioStorage().store("cancelled", source, null)
        }
        started.await()
        source.writeFully(byteArrayOf(1, 2))
        yield()
        job.cancel()
        job.join()
        assertEquals(null, location)
        source.cancel(null)
    }

    @Test
    fun anotherRepositoryCannotReadAnUncommittedTransaction() = runTest {
        val handle = createWebDatabase()
        val client = HttpClient(MockEngine { error("No network expected") })
        val id = "locked-${Random.nextInt(1, Int.MAX_VALUE)}"
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val write = async {
                handle.access.run(Dispatchers.Default) {
                    transaction {
                        appDatabaseQueries.insertContent(id = id, title = "Atomic", audioUrl = "audio",
                            localAudioPath = null, sourceUrl = "source", durationSeconds = 1, createdAt = 1)
                        entered.complete(Unit)
                        resume.await()
                        appDatabaseQueries.insertSubtitleLine(id = "$id-line", contentId = id,
                            startMs = 0, endMs = 1000, textDe = "Hallo", textEn = null)
                    }
                }
            }
            entered.await()
            val read = async {
                SqlDelightContentRepository(handle.access, ContentApi(client, "https://backend"),
                    IndexedDbAudioStorage(), { 0L }).getContentById(id)
            }
            yield()
            assertFalse(read.isCompleted)
            resume.complete(Unit)
            write.await()
            assertEquals(1, read.await()!!.subtitles.size)
        } finally { resume.complete(Unit); handle.close(); client.close() }
    }
}
