package com.langkraft.backend

import io.ktor.server.config.MapApplicationConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MediaBudgetTest {
    @TempDir lateinit var root: Path
    private fun id() = UUID.randomUUID().toString()
    private fun storage(total: Long = 2048) = BackendMediaStorage(root.toString(), limits = MediaLimits(1024, total))

    @Test fun parallelAdmissionsCannotReserveTheSameFreeSpace() {
        val storage = storage(1536)
        val pool = Executors.newFixedThreadPool(8)
        val leases = mutableListOf<Pair<String, AutoCloseable>>()
        try {
            val tasks = (1..8).map {
                pool.submit<Pair<String, AutoCloseable>?> {
                    val id = id()
                    try { id to storage.beginImport(id) } catch (failure: MediaCapacityException) { null }
                }
            }
            leases += tasks.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, leases.size)
            assertEquals(1L, Files.list(root).use { it.count() })
        } finally {
            leases.forEach { (id, lease) -> storage.discardImport(id); lease.close() }
            pool.shutdownNow()
        }
    }

    @Test fun activeReservationIsNotDoubleCountedAndPublishedUsageBecomesActual() {
        val storage = storage(1536)
        val first = id()
        storage.beginImport(first).use {
            Files.write(root.resolve("$first/video.mp3"), ByteArray(600))
            storage.checkImportBudget(first) // Reservation + its own 600 bytes must still fit 1536.
            assertFailsWith<MediaCapacityException> { storage.beginImport(id()) }
            Files.write(root.resolve("$first/video.mp3"), ByteArray(100))
            storage.publish(first)
        }
        val second = id()
        storage.beginImport(second).use {
            storage.checkImportBudget(second)
            assertFailsWith<MediaCapacityException> { storage.beginImport(id()) }
            storage.discardImport(second)
        }
        val third = id()
        storage.beginImport(third).use { storage.discardImport(third) }
    }

    @Test fun legacyAndUnknownFilesCountButAreNeverEvictedForAdmission() {
        Files.write(root.resolve("legacy.opus"), ByteArray(1200))
        val storage = storage()
        assertFailsWith<MediaCapacityException> { storage.beginImport(id()) }
        assertEquals(1200L, Files.size(root.resolve("legacy.opus")))
        assertEquals(1L, Files.list(root).use { it.count() })
    }

    @Test fun partialIntermediateAndLogBytesCannotPublishOverBudget() {
        val storage = storage()
        val id = id()
        storage.beginImport(id).use {
            Files.write(root.resolve("$id/source.part"), ByteArray(700))
            Files.write(root.resolve("$id/video.mp3"), ByteArray(200))
            Files.write(root.resolve("$id/process.log"), ByteArray(200))
            assertFailsWith<MediaLimitException> { storage.checkImportBudget(id) }
            var committed = false
            assertFailsWith<MediaLimitException> { storage.publish(id) { committed = true } }
            assertFalse(committed)
            storage.discardImport(id)
        }
        assertFalse(Files.exists(root.resolve(id)))
    }

    @Test fun failedCleanupDoesNotPretendToFreeDiskSpace() {
        var clock = 1000L
        var failDelete = true
        val storage = BackendMediaStorage(root.toString(), 100, { clock }, MediaLimits(1024, 2048)) {
            if (it.fileName.toString() == "source.part" && failDelete) throw java.io.IOException("busy")
            Files.delete(it)
        }
        val id = id()
        storage.beginImport(id).use {
            Files.write(root.resolve("$id/source.part"), ByteArray(2000))
            assertFailsWith<MediaLimitException> { storage.checkImportBudget(id) }
            assertFailsWith<java.io.IOException> { storage.discardImport(id) }
        }
        assertFailsWith<MediaCapacityException> { storage.beginImport(id()) }
        clock += 1000
        failDelete = false
        val next = id()
        storage.beginImport(next).use {
            assertFalse(Files.exists(root.resolve(id)), "Admission can reclaim only expired managed files")
            storage.discardImport(next)
        }
    }

    @Test fun completedImportRetainsOnlyAudioAndOwnershipMarker() {
        val storage = storage()
        val id = id()
        storage.beginImport(id).use {
            val audio = root.resolve("$id/video.mp3")
            Files.writeString(audio, "audio")
            Files.writeString(root.resolve("$id/source.webm"), "source")
            Files.writeString(root.resolve("$id/subtitles.srt"), "subtitle")
            storage.retainAudio(id, audio.toFile())
            assertEquals(setOf("video.mp3", ".langkraft-media-v1"), Files.list(root.resolve(id)).use {
                it.map { path -> path.fileName.toString() }.toList().toSet()
            })
            storage.publish(id)
        }
        assertNotNull(storage.acquireMedia(listOf(id, "video.mp3"))).close()
    }

    @Test fun validatesConfigurationAndDisabledBudgetKeepsExistingBehavior() {
        assertFailsWith<IllegalArgumentException> { MediaLimits(maxTotalBytes = 1024) }
        assertFailsWith<IllegalArgumentException> { MediaLimits(2048, 1024) }
        assertFailsWith<IllegalArgumentException> { MediaLimits(maxImportBytes = -1) }
        assertFailsWith<IllegalArgumentException> { MediaLimits(checkIntervalMs = 0) }
        assertFailsWith<IllegalArgumentException> { MediaLimits(maxDurationSeconds = 0) }
        assertEquals(MediaLimits(1024, 2048, 30, 100), MediaLimits.from(MapApplicationConfig(
            "storage.max_import_bytes" to "1024", "storage.max_total_bytes" to "2048",
            "ingestion.max_duration_seconds" to "30", "storage.budget_check_interval_ms" to "100")))
        val storage = BackendMediaStorage(root.toString())
        val id = id()
        storage.beginImport(id).use {
            Files.write(root.resolve("$id/video.mp3"), ByteArray(4096))
            storage.checkImportBudget(id)
            storage.discardImport(id)
        }
    }

    @Test fun admissionRejectsABudgetTooSmallForItsMarkerWithoutLeavingFilesOrAReservation() {
        val storage = BackendMediaStorage(root.toString(), limits = MediaLimits(1, 1))
        repeat(2) { assertFailsWith<MediaCapacityException> { storage.beginImport(id()) } }
        assertEquals(0L, Files.list(root).use { it.count() })
    }
}
