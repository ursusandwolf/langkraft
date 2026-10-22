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
import kotlin.test.*

class IngestionAdmissionTest {
    @TempDir lateinit var directory: Path
    private lateinit var db: Database
    private lateinit var repository: IngestionJobRepository
    private val url = "https://www.youtube.com/watch?v=video"
    private val config get() = MapApplicationConfig("ingestion.cleanup_interval_ms" to "3600000",
        "ingestion.job_expiry_ms" to "86400000", "ingestion.worker_count" to "1", "ingestion.queue_capacity" to "1")

    @BeforeEach fun setup() {
        db = Database.connect("jdbc:sqlite:${directory.resolve("jobs.db")}", "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(IngestionJobs) }
        repository = IngestionJobRepository(db)
    }

    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(10) }
    private fun countJobs() = transaction(db) { IngestionJobs.selectAll().count() }

    @Test fun invalidUrlsNeverCreateJobsOrStartProcesses() = runBlocking {
        val runner = ProcessRunner { error("Must not execute") }
        val service = YouTubeIngestionService(YtdlpClient(directory.toString(), runner), repository, config, this)
        try {
            listOf("http://youtu.be/video", "https://youtube.com.evil/watch?v=video", "https://youtube.com/playlist?list=x",
                "https://user@youtube.com/watch?v=video", "file:///etc/passwd", "https://youtube.com:444/watch?v=video").forEach {
                assertFailsWith<IngestionException> { service.startIngestion(it) }
            }
            assertEquals(0L, countJobs())
        } finally { service.close() }
    }

    @Test fun capacityRejectsBeforePersistenceAndShutdownMarksJobs(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runner = ProcessRunner { entered.complete(Unit); awaitCancellation() }
        val service = YouTubeIngestionService(YtdlpClient(directory.toString(), runner), repository, config, this)
        try {
            val active = service.startIngestion(url)
            entered.await()
            val queued = service.startIngestion(url)
            assertFailsWith<IngestionBusyException> { service.startIngestion(url) }
            assertEquals(2L, countJobs())
            service.close()
            waitFor { repository.get(active)?.status == ContentProcessingStatus.ERROR }
            assertEquals(ContentProcessingStatus.ERROR, repository.get(queued)?.status)
            assertFailsWith<IngestionBusyException> { service.startIngestion(url) }
        } finally { service.close() }
    }

    @Test fun failedJobReleasesSlotAndWorkerContinues() = runBlocking {
        val runner = ProcessRunner { throw IngestionException("failure") }
        val service = YouTubeIngestionService(YtdlpClient(directory.toString(), runner), repository, config, this)
        try {
            repeat(3) {
                val id = service.startIngestion(url)
                waitFor { repository.get(id)?.status == ContentProcessingStatus.ERROR }
            }
            assertEquals(3L, countJobs())
        } finally { service.close() }
    }

    @Test fun importsKeepPollingContractAndSeparateFilesForSameVideo() = runBlocking {
        val runner = ProcessRunner { arguments ->
            if ("--dump-single-json" in arguments) """{"id":"video","title":"Title","duration":1.25}"""
            else {
                assertTrue("--ignore-config" in arguments && "--no-playlist" in arguments)
                val template = arguments[arguments.indexOf("--output") + 1]
                File(template.replace("%(ext)s", "mp3")).writeText("audio")
                File(template.replace("%(ext)s", "de.srt")).writeText("1\n00:00:00,000 --> 00:00:01,000\nHallo\n")
                ""
            }
        }
        val waveform = WaveformExtractor(ProcessRunner { arguments -> File(arguments.last()).writeBytes(byteArrayOf(0, 1)); "" })
        val service = YouTubeIngestionService(YtdlpClient(directory.toString(), runner), repository, config, this, waveform)
        try {
            val first = service.startIngestion(url)
            waitFor { repository.get(first)?.status == ContentProcessingStatus.READY }
            val second = service.startIngestion(url)
            waitFor { repository.get(second)?.status == ContentProcessingStatus.READY }
            val one = requireNotNull(repository.get(first)?.content)
            val two = requireNotNull(repository.get(second)?.content)
            assertEquals("/api/media/$first/video.mp3", one.audioUrl)
            assertNotEquals(one.localAudioPath, two.localAudioPath)
            assertEquals(200, one.waveform.size)
            assertEquals(1, one.subtitles.size)
        } finally { service.close() }
    }

    @Test fun supportedVideoUrlsPassValidation() {
        listOf(url, "https://youtu.be/video?t=1", "https://m.youtube.com/shorts/video", "https://youtube.com/live/video", "https://youtube.com/embed/video")
            .forEach(SourceUrlValidator::validate)
    }
}
