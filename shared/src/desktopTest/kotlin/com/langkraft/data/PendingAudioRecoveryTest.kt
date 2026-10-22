package com.langkraft.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.data.audio.*
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.db.AppDatabase
import kotlinx.coroutines.*
import kotlin.test.*

class PendingAudioRecoveryTest {
    private val asset = PendingAudioAsset("indexeddb-audio:published", "owner", "web-lock-v1")

    private class Coordinator : AudioDownloadCoordinator {
        var busy = false
        var held = false
        var protocol: String? = "web-lock-v1"
        var afterAcquire: () -> Unit = {}
        override suspend fun tryAcquire(contentId: String): AudioDownloadLease? {
            if (busy) return null
            check(!held)
            held = true
            afterAcquire()
            return object : AudioDownloadLease {
                override val recoveryProtocol = protocol
                override suspend fun release() { check(held); held = false }
            }
        }
    }

    private class Storage(val asset: PendingAudioAsset, val owner: Coordinator) : PendingAudioStorage {
        var pending = true
        var deleted = false
        var failure: Throwable? = null
        var decision: Boolean? = null
        override suspend fun listPending() = if (pending) listOf(asset) else emptyList()
        override suspend fun resolvePending(location: String, referenced: Boolean): Boolean {
            check(owner.held)
            assertEquals(asset.location, location)
            failure?.let { throw it }
            decision = referenced
            pending = false
            deleted = !referenced
            return true
        }
    }

    private fun scenario(block: suspend (AppDatabase, Coordinator, Storage, PendingAudioRecovery, JdbcSqliteDriver) -> Unit) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            AppDatabase.Schema.create(driver).await()
            val db = AppDatabase(driver)
            val owner = Coordinator()
            val storage = Storage(asset, owner)
            block(db, owner, storage, PendingAudioRecovery(DatabaseAccess(db), owner, storage), driver)
        } finally { driver.close() }
    }

    @Test fun unreferencedPublicationIsDeletedOnceWithoutRequiringContentToExist() = scenario { _, owner, files, recovery, _ ->
        assertEquals(1, recovery.recover())
        assertTrue(files.deleted)
        assertFalse(files.pending)
        assertFalse(owner.held)
        assertEquals(0, recovery.recover())
    }

    @Test fun referenceFromAnotherContentPreservesAndConfirmsTheAsset() = scenario { db, owner, files, recovery, _ ->
        db.appDatabaseQueries.insertContent(id="other", title="Other", audioUrl="/remote.mp3", localAudioPath=asset.location,
            sourceUrl="https://youtu.be/other", durationSeconds=1, createdAt=0, downloadStatus="COMPLETED", waveform="[]")
        assertEquals(1, recovery.recover())
        assertEquals(true, files.decision)
        assertFalse(files.deleted)
        assertFalse(files.pending)
        assertFalse(owner.held)
    }

    @Test fun activePublisherIsNotResolved() = scenario { _, owner, files, recovery, _ ->
        owner.busy = true
        assertEquals(0, recovery.recover())
        assertTrue(files.pending)
        assertNull(files.decision)
    }

    @Test fun unknownOrUnavailableProtocolIsNotResolved() = scenario { _, owner, files, recovery, _ ->
        for (protocol in listOf(null, "future-protocol")) {
            owner.protocol = protocol
            assertEquals(0, recovery.recover())
            assertFalse(owner.held)
        }
        assertTrue(files.pending)
        assertNull(files.decision)
    }

    @Test fun sqlReadFailureDoesNotDeleteAnythingAndReleasesLease() = scenario { _, owner, files, recovery, driver ->
        driver.execute(null, "DROP TABLE Content", 0)
        assertFails { recovery.recover() }
        assertTrue(files.pending)
        assertNull(files.decision)
        assertFalse(owner.held)
    }

    @Test fun storageFailureKeepsPendingForTheNextAttemptAndReleasesLease() = scenario { _, owner, files, recovery, _ ->
        val failure = IllegalStateException("IndexedDB write failed")
        files.failure = failure
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { recovery.recover() }.message)
        assertTrue(files.pending)
        assertFalse(owner.held)
        files.failure = null
        assertEquals(1, recovery.recover())
        assertTrue(files.deleted)
    }

    @Test fun cancellationAfterAcquisitionDoesNotResolveAndReleasesLease() = scenario { _, owner, files, recovery, _ ->
        coroutineScope {
            val operation = launch(start = CoroutineStart.LAZY) { recovery.recover() }
            owner.afterAcquire = { operation.cancel() }
            operation.start()
            operation.join()
            assertTrue(operation.isCancelled)
        }
        assertTrue(files.pending)
        assertFalse(owner.held)
    }
}
