package com.langkraft.backend

import com.langkraft.backend.db.IngestionJobs
import com.langkraft.domain.model.ContentProcessingStatus
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class IngestionBudgetTest {
    @TempDir lateinit var root: Path
    private lateinit var repository: IngestionJobRepository
    private lateinit var db: Database
    private val url = "https://youtu.be/video"
    private val downloads get() = root.resolve("downloads").toFile()
    private val config = MapApplicationConfig("ingestion.cleanup_interval_ms" to "3600000",
        "ingestion.job_expiry_ms" to "86400000", "ingestion.worker_count" to "1", "ingestion.queue_capacity" to "1")

    @BeforeEach fun setup() {
        db = Database.connect("jdbc:sqlite:${root.resolve("jobs.db")}", "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(IngestionJobs) }
        repository = IngestionJobRepository(db)
    }

    private suspend fun finished(id: String) = withTimeout(10_000) {
        while (repository.get(id)?.status !in listOf(ContentProcessingStatus.READY, ContentProcessingStatus.ERROR)) delay(10)
        requireNotNull(repository.get(id))
    }

    private fun files(arguments: List<String>) {
        val template = arguments[arguments.indexOf("--output") + 1]
        File(template.replace("%(ext)s", "mp3")).writeText("audio")
        File(template.replace("%(ext)s", "de.srt")).writeText("1\n00:00:00,000 --> 00:00:01,000\nHallo\n")
        File(template.replace("%(ext)s", "webm")).writeBytes(ByteArray(100))
    }

    private fun waveform(bytes: Int = 2) = WaveformExtractor(ProcessRunner { arguments ->
        File(arguments.last()).writeBytes(ByteArray(bytes)); ""
    })

    @Test fun capacityRejectsBeforeJobPersistenceAndShutdownReleasesReservation() = runBlocking {
        val storage = BackendMediaStorage(downloads.path, limits = MediaLimits(1024, 1024))
        val entered = CompletableDeferred<Unit>()
        val service = YouTubeIngestionService(YtdlpClient(downloads.path, ProcessRunner {
            entered.complete(Unit); awaitCancellation()
        }, storage), repository, config, this)
        try {
            val id = service.startIngestion(url); entered.await()
            repeat(3) { assertFailsWith<MediaCapacityException> { service.startIngestion(url) } }
            assertEquals(1L, transaction(db) { IngestionJobs.selectAll().count() })
            service.close()
            finished(id)
            val probe = UUID.randomUUID().toString()
            storage.beginImport(probe).use { storage.discardImport(probe) }
        } finally { service.close() }
    }

    @Test fun knownOversizeLiveUnknownAndOverlongMetadataNeverStartDownload() = runBlocking {
        val metadata = listOf(
            """{"id":"video","duration":1,"filesize":1,"filesize_approx":2000}""",
            """{"id":"video","duration":1,"is_live":true}""",
            """{"id":"video"}""",
            """{"id":"video","duration":11}"""
        ).iterator()
        var downloadsStarted = 0
        val runner = ProcessRunner { arguments ->
            if ("--dump-single-json" in arguments) metadata.next()
            else { downloadsStarted++; error("Must reject before download") }
        }
        val storage = BackendMediaStorage(downloads.path, limits = MediaLimits(1024, 2048, 10))
        val service = YouTubeIngestionService(YtdlpClient(downloads.path, runner, storage), repository, config, this)
        try {
            repeat(4) { assertEquals(ContentProcessingStatus.ERROR, finished(service.startIngestion(url)).status) }
            assertEquals(0, downloadsStarted)
            assertTrue(downloads.listFiles().orEmpty().isEmpty())
        } finally { service.close() }
    }

    @Test fun growingDownloadIsCancelledCleanedAndWorkerCanProcessNextImport() = runBlocking {
        var oversized = true
        var cancelled = false
        val runner = ProcessRunner { arguments ->
            if ("--dump-single-json" in arguments) """{"id":"video","duration":1}"""
            else {
                files(arguments)
                if (oversized) {
                    oversized = false
                    val template = arguments[arguments.indexOf("--output") + 1]
                    File(template.replace("%(ext)s", "part")).writeBytes(ByteArray(4096))
                    try { awaitCancellation() } finally { cancelled = true }
                }
                ""
            }
        }
        val storage = BackendMediaStorage(downloads.path, limits = MediaLimits(1024, 2048, checkIntervalMs = 10))
        val service = YouTubeIngestionService(YtdlpClient(downloads.path, runner, storage), repository, config, this, waveform())
        try {
            val first = service.startIngestion(url)
            val failure = finished(first)
            assertEquals(ContentProcessingStatus.ERROR, failure.status)
            assertContains(failure.error.orEmpty(), "byte limit")
            assertTrue(cancelled)
            assertFalse(File(downloads, first).exists())
            val second = service.startIngestion(url)
            val ready = finished(second)
            assertEquals(ContentProcessingStatus.READY, ready.status)
            assertEquals("Hallo", ready.content?.subtitles?.single()?.originalText)
            assertEquals(setOf("video.mp3", ".langkraft-media-v1"), File(downloads, second).listFiles().orEmpty().map { it.name }.toSet())
        } finally { service.close() }
    }

    @Test fun waveformQuotaFailureIsNotDowngradedToSilentWaveformFallback() = runBlocking {
        val runner = ProcessRunner { arguments ->
            if ("--dump-single-json" in arguments) """{"id":"video","duration":1}"""
            else { files(arguments); "" }
        }
        val storage = BackendMediaStorage(downloads.path, limits = MediaLimits(1024, 2048))
        val service = YouTubeIngestionService(YtdlpClient(downloads.path, runner, storage), repository, config, this, waveform(4096))
        try {
            val id = service.startIngestion(url)
            val failure = finished(id)
            assertEquals(ContentProcessingStatus.ERROR, failure.status)
            assertContains(failure.error.orEmpty(), "byte limit")
            assertFalse(File(downloads, id).exists())
        } finally { service.close() }
    }
}
