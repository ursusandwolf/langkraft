package com.langkraft.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.data.repository.AudioDownloadException
import com.langkraft.data.repository.AudioDownloaderImpl
import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.audio.AudioDownloadCoordinator
import com.langkraft.data.audio.AudioDownloadLease
import com.langkraft.data.audio.AudioDownloadRecovery
import com.langkraft.data.audio.UncoordinatedAudioDownloads
import com.langkraft.data.database.DatabaseAccess
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import app.cash.sqldelight.Query
import kotlin.test.*

class AudioDownloaderTest {
    private val content = ImmersionContent("video", "Title", "/api/media/video.mp3", null, "https://youtu.be/video", 1)

    private class TestCoordinator : AudioDownloadCoordinator {
        var held = false
        override suspend fun tryAcquire(contentId: String): AudioDownloadLease? {
            if (held) return null
            held = true
            return object : AudioDownloadLease {
                override val recoveryProtocol = "web-lock-v1"
                override suspend fun release() { check(held); held = false }
            }
        }
    }

    private class MemoryFiles : AudioStorage {
        val files = mutableMapOf<String, ByteArray>()
        var cancelWrite = false
        var afterStore: suspend () -> Unit = {}
        var deleteFailure: Throwable? = null
        var afterConfirm: suspend (String) -> Unit = {}
        override suspend fun confirmLinked(location: String) { afterConfirm(location) }
        override suspend fun delete(location: String) {
            deleteFailure?.let { throw it }
            files.remove(location)
        }
        override suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?): String {
            val bytes = source.readRemaining().readBytes()
            if (bytes.isEmpty()) throw AudioDownloadException("Empty audio")
            if (expectedBytes != null && bytes.size.toLong() != expectedBytes) throw AudioDownloadException("Incomplete audio")
            if (cancelWrite) throw CancellationException("cancelled write")
            files["local/$contentId.mp3"] = bytes
            afterStore()
            return "local/$contentId.mp3"
        }
    }

    private fun scenario(status: HttpStatusCode, type: String?, body: String, cancel: Boolean = false,
                         coordinator: AudioDownloadCoordinator = UncoordinatedAudioDownloads,
                         configure: suspend (JdbcSqliteDriver) -> Unit = {},
                         verify: suspend (AudioDownloaderImpl, AppDatabase, MemoryFiles) -> Unit) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver).await()
        val db = AppDatabase(driver)
        db.appDatabaseQueries.insertContent(id="video", title="Title", audioUrl=content.audioUrl, localAudioPath=null, sourceUrl=content.sourceUrl, durationSeconds=1, createdAt=0, downloadStatus="IDLE", waveform="[]")
        configure(driver)
        val files = MemoryFiles().apply { cancelWrite = cancel }
        val client = HttpClient(MockEngine { request ->
            assertEquals("http://localhost:8080/api/media/video.mp3", request.url.toString())
            respond(body, status, if (type == null) headersOf() else headersOf(HttpHeaders.ContentType, type))
        })
        try { verify(AudioDownloaderImpl(client, files, DatabaseAccess(db), Url("http://localhost:8080"), coordinator), db, files) }
        finally { client.close(); driver.close() }
    }

    @Test fun rejectsHttpErrorBeforeWriting() = scenario(HttpStatusCode.ServiceUnavailable, "audio/mpeg", "error") { downloader, db, files ->
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        assertTrue(files.files.isEmpty())
        assertEquals("ERROR", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
    }

    @Test fun leaseProtectsDownloadingStateUntilSqlLinkAndMarkerIsRemovedOnSuccess() {
        val owner = TestCoordinator()
        scenario(HttpStatusCode.OK, "audio/mpeg", "audio", coordinator = owner) { downloader, db, files ->
            files.afterStore = {
                assertTrue(owner.held)
                assertEquals("web-lock-v1", db.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
                assertEquals(0, AudioDownloadRecovery(DatabaseAccess(db), owner).recover())
                assertEquals("DOWNLOADING", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
            }
            downloader.downloadAudio(content)
            assertFalse(owner.held)
            assertNull(db.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
            assertEquals("COMPLETED", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
        }
    }

    @Test fun failedRequestReleasesLeaseAndClearsRecoveryMarker() {
        val owner = TestCoordinator()
        scenario(HttpStatusCode.ServiceUnavailable, "audio/mpeg", "error", coordinator = owner) { downloader, db, _ ->
            assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
            assertFalse(owner.held)
            assertNull(db.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
        }
    }

    @Test fun cancelledStorageReleasesLeaseAndClearsRecoveryMarker() {
        val owner = TestCoordinator()
        scenario(HttpStatusCode.OK, "audio/mpeg", "audio", cancel = true, coordinator = owner) { downloader, db, _ ->
            assertFailsWith<CancellationException> { downloader.downloadAudio(content) }
            assertFalse(owner.held)
            assertNull(db.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
        }
    }

    @Test fun busyLeaseRejectsDownloadBeforeChangingSqlState() {
        val owner = TestCoordinator().apply { held = true }
        scenario(HttpStatusCode.OK, "audio/mpeg", "audio", coordinator = owner) { downloader, db, files ->
            assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
            assertEquals("IDLE", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
            assertTrue(owner.held)
            assertTrue(files.files.isEmpty())
        }
    }

    @Test fun markerFailureRollsBackDownloadingStateAndReleasesLease() {
        val owner = TestCoordinator()
        scenario(HttpStatusCode.OK, "audio/mpeg", "audio", coordinator = owner, configure = { driver ->
            driver.execute(null, "CREATE TRIGGER reject_marker BEFORE INSERT ON SyncMetadata BEGIN SELECT RAISE(FAIL, 'marker write failed'); END", 0)
        }) { downloader, db, files ->
            assertFails { downloader.downloadAudio(content) }
            assertEquals("IDLE", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
            assertNull(db.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
            assertFalse(owner.held)
            assertTrue(files.files.isEmpty())
        }
    }

    @Test fun confirmsPublicationOnlyAfterSqlLinkWhileHoldingTheLease() {
        val owner = TestCoordinator()
        scenario(HttpStatusCode.OK, "audio/mpeg", "audio", coordinator = owner) { downloader, db, files ->
            var confirmed = false
            files.afterConfirm = { location ->
                assertTrue(owner.held)
                val row = db.appDatabaseQueries.selectContentById("video").awaitAsOne()
                assertEquals("COMPLETED", row.downloadStatus)
                assertEquals(location, row.localAudioPath)
                confirmed = true
            }
            downloader.downloadAudio(content)
            assertTrue(confirmed)
            assertFalse(owner.held)
        }
    }

    @Test fun failedConfirmationPreservesCommittedAudioForStartupReconciliation() {
        val owner = TestCoordinator()
        scenario(HttpStatusCode.OK, "audio/mpeg", "audio", coordinator = owner) { downloader, db, files ->
            val failure = IllegalStateException("Cannot confirm pending audio")
            files.afterConfirm = { throw failure }
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { downloader.downloadAudio(content) }.message)
            val row = db.appDatabaseQueries.selectContentById("video").awaitAsOne()
            assertEquals("COMPLETED", row.downloadStatus)
            assertEquals("local/video.mp3", row.localAudioPath)
            assertTrue(files.files.containsKey(row.localAudioPath))
            assertFalse(owner.held)
        }
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

    @Test fun cancellationAfterCommittedLinkKeepsTheAudioAndCompletedStatus() = scenario(HttpStatusCode.OK, "audio/mpeg", "audio") { downloader, db, files ->
        var armed = false
        lateinit var operation: Job
        val query = db.appDatabaseQueries.selectContentById("video")
        val listener = object : Query.Listener {
            override fun queryResultsChanged() {
                if (armed) operation.cancel(CancellationException("cancel after SQL commit"))
            }
        }
        query.addListener(listener)
        files.afterStore = { armed = true }
        try {
            coroutineScope {
                operation = launch(start = CoroutineStart.LAZY) { downloader.downloadAudio(content) }
                operation.start()
                operation.join()
            }
            assertTrue(operation.isCancelled)
            val stored = query.awaitAsOne()
            assertEquals("COMPLETED", stored.downloadStatus)
            assertEquals("local/video.mp3", stored.localAudioPath)
            assertTrue(files.files.containsKey(stored.localAudioPath))
        } finally { query.removeListener(listener) }
    }

    @Test fun failedReplacementPreservesExistingOfflineAudio() = scenario(HttpStatusCode.ServiceUnavailable, "audio/mpeg", "error") { downloader, db, files ->
        files.files["old.mp3"] = byteArrayOf(9)
        db.appDatabaseQueries.updateDownloadStatus("COMPLETED", "old.mp3", "video")
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        val stored = db.appDatabaseQueries.selectContentById("video").awaitAsOne()
        assertEquals("old.mp3", stored.localAudioPath)
        assertEquals("COMPLETED", stored.downloadStatus)
        assertContentEquals(byteArrayOf(9), files.files["old.mp3"])
    }

    @Test fun contentDeletedDuringDownloadDoesNotPublishAnOrphan() = scenario(HttpStatusCode.OK, "audio/mpeg", "audio") { downloader, db, files ->
        files.afterStore = { db.appDatabaseQueries.deleteContent("video") }
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        assertTrue(files.files.isEmpty())
    }

    @Test fun failedLinkCleansNewAudioAndPreservesCleanupFailure() = scenario(HttpStatusCode.OK, "audio/mpeg", "audio", configure = { driver ->
        driver.execute(null, """
            CREATE TRIGGER reject_audio BEFORE UPDATE ON Content
            WHEN NEW.downloadStatus = 'COMPLETED'
            BEGIN SELECT RAISE(FAIL, 'simulated link failure'); END;
        """.trimIndent(), 0)
    }) { downloader, db, files ->
        val cleanupFailure = IllegalStateException("simulated delete failure")
        files.deleteFailure = cleanupFailure
        val failure = assertFails { downloader.downloadAudio(content) }
        assertTrue(failure.message.orEmpty().contains("simulated link failure"))
        assertTrue(failure.suppressedExceptions.contains(cleanupFailure))
        val stored = db.appDatabaseQueries.selectContentById("video").awaitAsOne()
        assertNull(stored.localAudioPath)
        assertEquals("ERROR", stored.downloadStatus)
    }

    @Test fun failedLinkRemovesOnlyTheNewFile() = scenario(HttpStatusCode.OK, "audio/mpeg", "audio", configure = { driver ->
        driver.execute(null, """
            CREATE TRIGGER reject_audio BEFORE UPDATE ON Content
            WHEN NEW.localAudioPath = 'local/video.mp3'
            BEGIN SELECT RAISE(FAIL, 'simulated link failure'); END;
        """.trimIndent(), 0)
    }) { downloader, db, files ->
        files.files["old.mp3"] = byteArrayOf(9)
        db.appDatabaseQueries.updateDownloadStatus("COMPLETED", "old.mp3", "video")
        assertFails { downloader.downloadAudio(content) }
        assertEquals(setOf("old.mp3"), files.files.keys)
        val stored = db.appDatabaseQueries.selectContentById("video").awaitAsOne()
        assertEquals("old.mp3", stored.localAudioPath)
        assertEquals("COMPLETED", stored.downloadStatus)
    }

    @Test fun compensationNeverDeletesAnAssetReferencedByAnotherContent() = scenario(HttpStatusCode.OK, "audio/mpeg", "audio") { downloader, db, files ->
        files.afterStore = {
            db.appDatabaseQueries.insertContent(id="other", title="Other", audioUrl=content.audioUrl, localAudioPath="local/video.mp3",
                sourceUrl=content.sourceUrl, durationSeconds=1, createdAt=1, downloadStatus="COMPLETED", waveform="[]")
            db.appDatabaseQueries.deleteContent("video")
        }
        assertFailsWith<AudioDownloadException> { downloader.downloadAudio(content) }
        assertEquals("local/video.mp3", db.appDatabaseQueries.selectContentById("other").awaitAsOne().localAudioPath)
        assertTrue(files.files.containsKey("local/video.mp3"))
    }
}
