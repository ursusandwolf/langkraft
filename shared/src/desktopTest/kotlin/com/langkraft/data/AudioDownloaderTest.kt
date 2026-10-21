package com.langkraft.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.data.repository.AudioDownloadException
import com.langkraft.data.repository.AudioDownloaderImpl
import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.io.FileSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AudioDownloaderTest {
    private val content = ImmersionContent("video", "Title", "/api/media/video.mp3", null, "https://youtu.be/video", 1)

    private class MemoryFiles : FileSystem {
        val files = mutableMapOf<String, ByteArray>()
        var cancelWrite = false
        override fun getAppDataDir() = "local"
        override fun exists(path: String) = path in files
        override fun delete(path: String) { files.remove(path) }
        override fun resolve(base: String, child: String) = "$base/$child"
        override fun writeBytes(path: String, bytes: ByteArray) {
            files[path] = bytes
            if (cancelWrite) throw CancellationException("cancelled write")
        }
        override fun rename(from: String, to: String) { files[to] = files.remove(from)!! }
    }

    private fun scenario(status: HttpStatusCode, type: String?, body: String, cancel: Boolean = false,
                         verify: suspend (AudioDownloaderImpl, AppDatabase, MemoryFiles) -> Unit) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val db = AppDatabase(driver)
        db.appDatabaseQueries.insertContent("video", "Title", content.audioUrl, null, content.sourceUrl, 1, 0, "IDLE")
        val files = MemoryFiles().apply { cancelWrite = cancel }
        val client = HttpClient(MockEngine { request ->
            assertEquals("http://localhost:8080/api/media/video.mp3", request.url.toString())
            respond(body, status, if (type == null) headersOf() else headersOf(HttpHeaders.ContentType, type))
        })
        try { verify(AudioDownloaderImpl(client, files, db, Url("http://localhost:8080")), db, files) }
        finally { client.close(); driver.close() }
    }

    @Test fun rejectsHttpErrorBeforeWriting() = scenario(HttpStatusCode.ServiceUnavailable, "audio/mpeg", "error") { downloader, db, files ->
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        assertTrue(files.files.isEmpty())
        assertEquals("ERROR", db.appDatabaseQueries.selectContentById("video").executeAsOne().downloadStatus)
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
        assertEquals("COMPLETED", db.appDatabaseQueries.selectContentById("video").executeAsOne().downloadStatus)
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
        assertEquals("IDLE", db.appDatabaseQueries.selectContentById("video").executeAsOne().downloadStatus)
    }
}
