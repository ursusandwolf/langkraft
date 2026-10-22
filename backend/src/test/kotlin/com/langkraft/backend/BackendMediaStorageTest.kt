package com.langkraft.backend

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class BackendMediaStorageTest {
    @TempDir lateinit var root: Path
    private var clock = 1000L
    private fun storage(retention: Long = 100) = BackendMediaStorage(root.toString(), retention, { clock })
    private fun ready(storage: BackendMediaStorage): String {
        val id = UUID.randomUUID().toString()
        storage.beginImport(id).use {
            Files.writeString(root.resolve("$id/video.mp3"), "audio")
            storage.publish(id)
        }
        return id
    }

    @Test fun expirationIsBasedOnPublicationAndDisabledByDefault() {
        val storage = storage()
        val id = UUID.randomUUID().toString()
        val lease = storage.beginImport(id)
        clock = 10_000
        assertTrue(storage.cleanup().deleted.isEmpty())
        Files.writeString(root.resolve("$id/video.mp3"), "audio")
        storage.publish(id); lease.close()
        clock += 99
        assertTrue(storage.cleanup().deleted.isEmpty())
        clock++
        assertTrue(BackendMediaStorage(root.toString()).cleanup().deleted.isEmpty())
        assertEquals(listOf(id), storage.cleanup().deleted)
        assertFalse(Files.exists(root.resolve(id)))
        assertTrue(storage.cleanup().deleted.isEmpty())
    }

    @Test fun allReadersMustCloseBeforeCleanupAndCloseIsIdempotent() {
        val storage = storage()
        val id = ready(storage)
        val first = assertNotNull(storage.acquireMedia(listOf(id, "video.mp3")))
        val second = assertNotNull(storage.acquireMedia(listOf(id, "video.mp3")))
        clock += 1000
        assertTrue(storage.cleanup().deleted.isEmpty())
        first.close(); first.close()
        assertTrue(storage.cleanup().deleted.isEmpty())
        second.close()
        assertEquals(listOf(id), storage.cleanup().deleted)
        assertNull(storage.acquireMedia(listOf(id, "video.mp3")))
    }

    @Test fun restartCleansAbandonedManagedImportsAndKeepsLegacyAndUnknownMarkers() {
        val storage = storage()
        val pending = UUID.randomUUID().toString()
        storage.beginImport(pending) // Simulated process exit: next instance has no active leases.
        Files.writeString(root.resolve("$pending/video.part"), "partial")
        val legacy = UUID.randomUUID().toString()
        Files.createDirectory(root.resolve(legacy))
        Files.writeString(root.resolve("$legacy/video.mp3"), "legacy")
        Files.writeString(root.resolve("legacy.mp3"), "legacy")
        Files.writeString(root.resolve("legacy.opus"), "legacy opus")
        val unknown = UUID.randomUUID().toString()
        Files.createDirectory(root.resolve(unknown))
        Files.writeString(root.resolve("$unknown/.langkraft-media-v1"), "unknown\n1000")
        clock += 1000
        assertEquals(listOf(pending), storage().cleanup().deleted)
        assertNotNull(storage.acquireMedia(listOf(legacy, "video.mp3"))).close()
        assertNotNull(storage.acquireMedia(listOf("legacy.mp3"))).close()
        assertNotNull(storage.acquireMedia(listOf("legacy.opus"))).close()
        assertTrue(Files.exists(root.resolve(unknown)))
        assertTrue(Files.exists(root.resolve(legacy)))
    }

    @Test fun partialDeletionFailureRetainsMarkerAndCanBeRetried() {
        var fail = true
        val storage = BackendMediaStorage(root.toString(), 100, { clock }) {
            if (it.fileName.toString() == "video.mp3" && fail) throw java.io.IOException("delete failed")
            Files.delete(it)
        }
        val id = ready(storage)
        clock += 1000
        assertEquals(listOf(id), storage.cleanup().failures)
        assertTrue(Files.isRegularFile(root.resolve("$id/.langkraft-media-v1")))
        fail = false
        assertEquals(listOf(id), storage.cleanup().deleted)
    }

    @Test fun failureToRemoveFinalDirectoryRestoresMarkerForRetry() {
        var fail = true
        val storage = BackendMediaStorage(root.toString(), 100, { clock }) {
            if (Files.isDirectory(it) && fail) throw java.io.IOException("directory busy")
            Files.delete(it)
        }
        val id = ready(storage)
        clock += 1000
        assertEquals(listOf(id), storage.cleanup().failures)
        assertTrue(Files.isRegularFile(root.resolve("$id/.langkraft-media-v1")))
        fail = false
        assertEquals(listOf(id), storage.cleanup().deleted)
    }

    @Test fun pendingMediaAndUnsafePathsAreNeverServed() {
        val storage = storage()
        val id = UUID.randomUUID().toString()
        storage.beginImport(id).use {
            Files.writeString(root.resolve("$id/video.mp3"), "audio")
            assertNull(storage.acquireMedia(listOf(id, "video.mp3")))
        }
        for (path in listOf(listOf("..", "secret.mp3"), listOf("/secret.mp3"), listOf(id, "..", "video.mp3"),
            listOf(id, ".langkraft-media-v1"), listOf(id, "video.part"), listOf("C:\\secret.mp3"))) {
            assertNull(storage.acquireMedia(path))
        }
        assertFailsWith<IllegalArgumentException> { storage.beginImport("../escape") }
        assertFailsWith<IllegalArgumentException> { BackendMediaStorage(root.toString(), -1) }
    }

    @Test fun failedReadyCommitKeepsImportOwnedAndUnavailableUntilCompensation() {
        val storage = storage()
        val id = UUID.randomUUID().toString()
        storage.beginImport(id).use {
            Files.writeString(root.resolve("$id/video.mp3"), "audio")
            assertFailsWith<IllegalStateException> { storage.publish(id) { error("SQL commit failed") } }
            clock += 1000
            assertNull(storage.acquireMedia(listOf(id, "video.mp3")))
            assertTrue(storage.cleanup().deleted.isEmpty())
            storage.discardImport(id)
        }
        assertFalse(Files.exists(root.resolve(id)))
    }

    @Test fun symlinksAreNotServedOrFollowedByCleanup() {
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("secret.mp3"), "keep")
        val storage = storage()
        val id = ready(storage)
        try { Files.createSymbolicLink(root.resolve("$id/link"), outside) }
        catch (failure: java.nio.file.FileSystemException) { assumeTrue(false, "Symlink privilege unavailable"); return }
        catch (failure: UnsupportedOperationException) { assumeTrue(false, "Symlinks unavailable"); return }
        clock += 1000
        assertEquals(listOf(id), storage.cleanup().failures)
        assertEquals("keep", Files.readString(outside.resolve("secret.mp3")))
        assertTrue(Files.isRegularFile(root.resolve("$id/video.mp3")))
        Files.createSymbolicLink(root.resolve("linked.mp3"), outside.resolve("secret.mp3"))
        assertNull(storage.acquireMedia(listOf("linked.mp3")))
    }
}
