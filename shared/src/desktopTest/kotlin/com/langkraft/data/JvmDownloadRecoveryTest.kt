package com.langkraft.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.audio.FileAudioDownloadCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertContentEquals
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.langkraft.data.audio.FileAudioStorage
import com.langkraft.data.repository.AudioDownloaderImpl
import com.langkraft.domain.model.ImmersionContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*

class JvmDownloadRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun reopeningDoesNotLeaveProtocolMarkedDownloadsStuckForever() = runBlocking {
        val file = File(folder.root, "langkraft.db")
        val initial = createJvmDatabase(file)
        initial.database.appDatabaseQueries.insertContent(id="video", title="Title", audioUrl="/audio.mp3", localAudioPath=null,
            sourceUrl="https://youtu.be/video", durationSeconds=1, createdAt=0, downloadStatus="DOWNLOADING", waveform="[]")
        initial.database.appDatabaseQueries.setSyncMetadata("audio_download_lock:video", "jvm-file-lock-v1")
        initial.close()
        val reopened = createJvmDatabase(file)
        try { assertEquals("ERROR", reopened.database.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus) }
        finally { reopened.close() }
    }

    @Test fun recoveryPreservesOldAudioAndLeavesLegacyUnknownAndCompletedStatesUntouched() = runBlocking {
        val file = File(folder.root, "langkraft.db")
        val audio = folder.newFile("old.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val initial = createJvmDatabase(file)
        try {
            for (id in listOf("replacement", "legacy", "unknown", "completed")) {
                initial.database.appDatabaseQueries.insertContent(id=id, title=id, audioUrl="/audio.mp3",
                    localAudioPath=if (id in listOf("replacement", "completed")) audio.path else null,
                    sourceUrl="https://youtu.be/video", durationSeconds=1, createdAt=0,
                    downloadStatus=if (id == "completed") "COMPLETED" else "DOWNLOADING", waveform="[]")
                if (id != "legacy") initial.database.appDatabaseQueries.setSyncMetadata("audio_download_lock:$id",
                    if (id == "unknown") "future-protocol" else "jvm-file-lock-v1")
            }
        } finally { initial.close() }
        val reopened = createJvmDatabase(file)
        try {
            val queries = reopened.database.appDatabaseQueries
            assertEquals("COMPLETED", queries.selectContentById("replacement").awaitAsOne().downloadStatus)
            assertEquals(audio.path, queries.selectContentById("replacement").awaitAsOne().localAudioPath)
            assertNull(queries.getSyncMetadata("audio_download_lock:replacement").awaitAsOneOrNull())
            assertEquals("DOWNLOADING", queries.selectContentById("legacy").awaitAsOne().downloadStatus)
            assertEquals("DOWNLOADING", queries.selectContentById("unknown").awaitAsOne().downloadStatus)
            assertEquals("COMPLETED", queries.selectContentById("completed").awaitAsOne().downloadStatus)
            assertContentEquals(byteArrayOf(1, 2, 3), audio.readBytes())
        } finally { reopened.close() }
    }

    @Test fun activeProcessIsPreservedAndItsInterruptedStateRecoversAfterForcedExit() = runBlocking {
        val file = File(folder.root, "langkraft.db")
        val locks = File(folder.root, "download-locks")
        val coordinator = FileAudioDownloadCoordinator(locks)
        val preparation = assertNotNull(coordinator.tryAcquire("video"))
        preparation.release()
        val initial = createJvmDatabase(file)
        initial.database.appDatabaseQueries.insertContent(id="video", title="Title", audioUrl="/audio.mp3", localAudioPath=null,
            sourceUrl="https://youtu.be/video", durationSeconds=1, createdAt=0, downloadStatus="DOWNLOADING", waveform="[]")
        initial.database.appDatabaseQueries.setSyncMetadata("audio_download_lock:video", "jvm-file-lock-v1")
        initial.close()
        val probe = NativeFileLockProbe(folder.root)
        val child = probe.start(locks.listFiles()!!.single(), hold = true)
        try {
            assertEquals("HELD", probe.ready(child))
            assertNull(coordinator.tryAcquire("video"))
            val during = createJvmDatabase(file)
            try { assertEquals("DOWNLOADING", during.database.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus) }
            finally { during.close() }
        } finally { probe.stop(child) }
        val after = createJvmDatabase(file)
        try {
            assertEquals("ERROR", after.database.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
            assertNull(after.database.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
        } finally { after.close() }
    }

    @Test fun downloaderHoldsNativeLeaseAndPersistsItsProtocolUntilTheFileIsLinked() = runBlocking {
        val coordinator = FileAudioDownloadCoordinator(File(folder.root, "download-locks"))
        val handle = createJvmDatabase(File(folder.root, "langkraft.db"), coordinator)
        val content = ImmersionContent("video", "Title", "/audio.mp3", null, "https://youtu.be/video", 1)
        handle.database.appDatabaseQueries.insertContent(id=content.id, title=content.title, audioUrl=content.audioUrl,
            localAudioPath=null, sourceUrl=content.sourceUrl, durationSeconds=1, createdAt=0, downloadStatus="IDLE", waveform="[]")
        val client = HttpClient(MockEngine {
            assertNull(coordinator.tryAcquire("video"))
            assertEquals("jvm-file-lock-v1", handle.database.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
            respond("audio", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpeg"))
        })
        try {
            val location = AudioDownloaderImpl(client, FileAudioStorage(File(folder.root, "audio")), handle.access,
                Url("http://localhost:8080"), coordinator).downloadAudio(content)
            assertContentEquals("audio".toByteArray(), File(location).readBytes())
            assertEquals("COMPLETED", handle.database.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
            assertNull(handle.database.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
            assertNotNull(coordinator.tryAcquire("video")).release()
        } finally { client.close(); handle.close() }
    }
}
