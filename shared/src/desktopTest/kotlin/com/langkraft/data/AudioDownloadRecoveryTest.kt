package com.langkraft.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.data.audio.*
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.db.AppDatabase
import kotlinx.coroutines.*
import kotlin.test.*

class AudioDownloadRecoveryTest {
    private class Coordinator : AudioDownloadCoordinator {
        var busy = false
        var protocol: String? = "web-lock-v1"
        var held = false
        var afterAcquire: () -> Unit = {}
        override suspend fun tryAcquire(contentId: String): AudioDownloadLease? {
            if (busy || held) return null
            held = true
            afterAcquire()
            return object : AudioDownloadLease {
                override val recoveryProtocol = protocol
                override suspend fun release() { check(held); held = false }
            }
        }
    }

    private fun scenario(block: suspend (AppDatabase, Coordinator, AudioDownloadRecovery, JdbcSqliteDriver) -> Unit) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            AppDatabase.Schema.create(driver).await()
            val db = AppDatabase(driver)
            db.appDatabaseQueries.insertContent(id="video", title="Title", audioUrl="/audio.mp3", localAudioPath=null,
                sourceUrl="https://youtu.be/video", durationSeconds=1, createdAt=0, downloadStatus="DOWNLOADING", waveform="[]")
            db.appDatabaseQueries.setSyncMetadata("audio_download_lock:video", "web-lock-v1")
            val coordinator = Coordinator()
            block(db, coordinator, AudioDownloadRecovery(DatabaseAccess(db), coordinator), driver)
        } finally { driver.close() }
    }

    @Test fun interruptedInitialDownloadBecomesRetryableAndRecoveryIsIdempotent() = scenario { db, owner, recovery, _ ->
        assertEquals(1, recovery.recover())
        val row = db.appDatabaseQueries.selectContentById("video").awaitAsOne()
        assertEquals("ERROR", row.downloadStatus)
        assertNull(row.localAudioPath)
        assertNull(db.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
        assertFalse(owner.held)
        assertEquals(0, recovery.recover())
    }

    @Test fun interruptedReplacementPreservesPreviouslyLinkedAudio() = scenario { db, _, recovery, _ ->
        db.appDatabaseQueries.updateDownloadStatus("DOWNLOADING", "old.mp3", "video")
        assertEquals(1, recovery.recover())
        val row = db.appDatabaseQueries.selectContentById("video").awaitAsOne()
        assertEquals("COMPLETED", row.downloadStatus)
        assertEquals("old.mp3", row.localAudioPath)
    }

    @Test fun activeOwnerIsUntouched() = scenario { db, owner, recovery, _ ->
        owner.busy = true
        assertEquals(0, recovery.recover())
        assertEquals("DOWNLOADING", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
        assertEquals("web-lock-v1", db.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
    }

    @Test fun legacyAndUnknownProtocolAreUntouched() = scenario { db, owner, recovery, _ ->
        db.appDatabaseQueries.deleteSyncMetadata("audio_download_lock:video")
        assertEquals(0, recovery.recover())
        db.appDatabaseQueries.setSyncMetadata("audio_download_lock:video", "future-protocol")
        assertEquals(0, recovery.recover())
        assertEquals("DOWNLOADING", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
        assertFalse(owner.held)
    }

    @Test fun unavailableCoordinationDisablesRecovery() = scenario { db, owner, recovery, _ ->
        owner.protocol = null
        assertEquals(0, recovery.recover())
        assertEquals("DOWNLOADING", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
        assertFalse(owner.held)
    }

    @Test fun committedDownloadIsUntouched() = scenario { db, _, recovery, _ ->
        db.appDatabaseQueries.updateDownloadStatus("COMPLETED", "new.mp3", "video")
        assertEquals(0, recovery.recover())
        assertEquals("new.mp3", db.appDatabaseQueries.selectContentById("video").awaitAsOne().localAudioPath)
    }

    @Test fun sqlFailureRollsBackAndReleasesLease() = scenario { db, owner, recovery, driver ->
        // The status UPDATE already ran when deleting the marker fails: both
        // writes must roll back together, so the next startup can retry safely.
        driver.execute(null, "CREATE TRIGGER reject_recovery BEFORE DELETE ON SyncMetadata BEGIN SELECT RAISE(FAIL, 'disk failure'); END", 0)
        assertFails { recovery.recover() }
        assertFalse(owner.held)
        assertEquals("DOWNLOADING", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
        assertEquals("web-lock-v1", db.appDatabaseQueries.getSyncMetadata("audio_download_lock:video").awaitAsOneOrNull())
    }

    @Test fun cancellationAfterAcquisitionReleasesLeaseWithoutResettingState() = scenario { db, owner, recovery, _ ->
        coroutineScope {
            val operation = launch(start = CoroutineStart.LAZY) { recovery.recover() }
            owner.afterAcquire = { operation.cancel() }
            operation.start()
            operation.join()
            assertTrue(operation.isCancelled)
        }
        assertFalse(owner.held)
        assertEquals("DOWNLOADING", db.appDatabaseQueries.selectContentById("video").awaitAsOne().downloadStatus)
    }
}
