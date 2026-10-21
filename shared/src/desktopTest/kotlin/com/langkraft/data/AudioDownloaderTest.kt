package com.langkraft.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.data.repository.AudioDownloadException
import com.langkraft.data.repository.AudioDownloaderImpl
import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.database.DatabaseAccess
import app.cash.sqldelight.async.coroutines.awaitAsOne
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AudioDownloaderTest {
    private val content = ImmersionContent("video", "Title", "/api/media/video.mp3", null, "https://youtu.be/video", 1)

    private class MemoryFiles : AudioStorage {
        val files = mutableMapOf<String, ByteArray>()
        var cancelWrite = false
        override suspend fun delete(location: String) { files.remove(location) }
        override suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?): String {
            val bytes = source.readRemaining().readBytes()
            if (bytes.isEmpty()) throw AudioDownloadException("Empty audio")
            if (expectedBytes != null && bytes.size.toLong() != expectedBytes) throw AudioDownloadException("Incomplete audio")
            if (cancelWrite) throw CancellationException("cancelled write")
            files["local/$contentId.mp3"] = bytes
            return "local/$contentId.mp3"
        }
    }

    private fun scenario(status: HttpStatusCode, type: String?, body: String, cancel: Boolean = false,
                         verify: suspend (AudioDownloaderImpl, AppDatabase, MemoryFiles) -> Unit) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver).await()
        val db = AppDatabase(driver)
        db.appDatabaseQueries.insertContent(id="video", title="Title", audioUrl=content.audioUrl, localAudioPath=null, sourceUrl=content.sourceUrl, durationSeconds=1, createdAt=0, downloadStatus="IDLE", waveform="[]")
        val files = MemoryFiles().apply { cancelWrite = cancel }
        val client = HttpClient(MockEngine { request ->
            assertEquals("http://localhost:8080/api/media/video.mp3", request.url.toString())
            respond(body, status, if (type == null) headersOf() else headersOf(HttpHeaders.ContentType, type))
        })
        try { verify(AudioDownloaderImpl(client, files, DatabaseAccess(db), Url("http://localhost:8080")), db, files) }
        finally { client.close(); driver.close() }
    }

    @Test fun rejectsHttpErrorBeforeWriting() = scenario(HttpStatusCode.ServiceUnavailable, "audio/mpeg", "error") { downloader, db, files ->
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        assertTrue(files.files.isEmpty())
        assertEquals("ERROR", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
    }

    @Test fun rejectsHtmlBeforeWriting() = scenario(HttpStatusCode.OK, "text/html; charset=utf-8", "<html>login</html>") { downloader, _, files ->
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        assertTrue(files.files.isEmpty())
    }

    @Test fun rejectsEmptyAudio() = scenario(HttpStatusCode.OK, "audio/mpeg", "") { downloader, _, files ->
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        assertTrue(files.files.isEmpty())
    }

    @Test fun rejectsPartialResponseWithoutRangeRequest() = scenario(HttpStatusCode.PartialContent, "audio/mpeg", "partial") { downloader, _, files ->
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        assertTrue(files.files.isEmpty())
    }

    @Test fun commitsMp3AfterValidation() = scenario(HttpStatusCode.OK, "audio/mpeg", "audio") { downloader, db, files ->
        assertEquals("local/video.mp3", downloader.downloadAudio(content))
        assertEquals(setOf("local/video.mp3"), files.files.keys)
        assertEquals("COMPLETED", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
    }

    @Test fun permitsGenericBinaryMime() = scenario(HttpStatusCode.OK, "application/octet-stream", "audio") { downloader, _, _ ->
        assertEquals("local/video.mp3", downloader.downloadAudio(content))
    }

    @Test fun permitsMissingMime() = scenario(HttpStatusCode.OK, null, "audio") { downloader, _, _ ->
        assertEquals("local/video.mp3", downloader.downloadAudio(content))
    }

    @Test fun cancellationRemovesTemporaryFileAndResetsStatus() = scenario(HttpStatusCode.OK, "audio/mpeg", "audio", cancel = true) { downloader, db, files ->
        assertFailsWith<CancellationException> { downloader.downloadAudio(content) }
        assertTrue(files.files.isEmpty())
        assertEquals("IDLE", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
    }
}
