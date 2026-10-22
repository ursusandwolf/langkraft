package com.langkraft.backend

import com.langkraft.domain.model.*
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Semaphore

/** Bounded admission with persistent job status; accepted POSTs outlive the request. */
class YouTubeIngestionService(
    private val ytdlpClient: YtdlpClient,
    private val jobRepository: IngestionJobRepository,
    config: ApplicationConfig,
    externalScope: CoroutineScope? = null,
    private val waveformExtractor: WaveformExtractor = WaveformExtractor()
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(YouTubeIngestionService::class.java)
    private val lifetime = SupervisorJob(externalScope?.coroutineContext?.get(Job))
    private val scope = CoroutineScope((externalScope?.coroutineContext ?: Dispatchers.IO) + lifetime)
    private val cleanupIntervalMs = config.property("ingestion.cleanup_interval_ms").getString().toLong().also { require(it > 0) }
    private val jobExpiryMs = config.property("ingestion.job_expiry_ms").getString().toLong().also { require(it > 0) }
    private val workerCount = config.property("ingestion.worker_count").getString().toInt().also { require(it in 1..64) }
    private val queueCapacity = (config.propertyOrNull("ingestion.queue_capacity")?.getString()?.toInt() ?: 8).also { require(it in 0..1024) }
    private val slots = Semaphore(workerCount + queueCapacity)
    private data class QueuedImport(val jobId: String, val url: String, val lease: AutoCloseable)
    private val ingestionChannel = Channel<QueuedImport>(workerCount + queueCapacity)
    private var closed = false

    init {
        jobRepository.recoverInterrupted()
        try { ytdlpClient.mediaStorage.cleanup() }
        catch (exception: Exception) { logger.error("Startup media cleanup failed", exception) }
        scope.launch {
            while (isActive) {
                delay(cleanupIntervalMs)
                try { ytdlpClient.mediaStorage.cleanup(); jobRepository.cleanup(jobExpiryMs) }
                catch (exception: CancellationException) { throw exception }
                catch (exception: Exception) { logger.error("Import cleanup failed", exception) }
            }
        }
        repeat(workerCount) {
            scope.launch {
                for ((jobId, url, lease) in ingestionChannel) {
                    try {
                        processWithBudget(jobId, url)
                    } catch (exception: CancellationException) {
                        failJob(jobId, "Import interrupted")
                        throw exception
                    } catch (exception: MediaLimitException) {
                        failJob(jobId, exception.message.orEmpty())
                    } catch (exception: Exception) {
                        logger.error("Ingestion failed for $jobId", exception)
                        failJob(jobId, "Import failed")
                    } finally { lease.close(); slots.release() }
                }
            }
        }
    }

    @Synchronized
    fun startIngestion(url: String): String {
        SourceUrlValidator.validate(url)
        if (closed || !lifetime.isActive || !slots.tryAcquire()) throw IngestionBusyException()
        val jobId = UUID.randomUUID().toString()
        var lease: AutoCloseable? = null
        try {
            lease = ytdlpClient.mediaStorage.beginImport(jobId)
            val now = System.currentTimeMillis()
            jobRepository.save(IngestionJob(jobId, ContentProcessingStatus.IDLE, url, createdAt = now, lastUpdatedAt = now))
            check(ingestionChannel.trySend(QueuedImport(jobId, url, lease)).isSuccess) { "Import queue is closed" }
            return jobId
        } catch (exception: Exception) {
            slots.release()
            if (lease != null) {
                failJob(jobId, "Import was not queued")
                lease.close()
            }
            throw exception
        }
    }

    fun getJobStatus(jobId: String): IngestionJob? = jobRepository.get(jobId)

    private suspend fun processWithBudget(jobId: String, url: String) {
        val media = ytdlpClient.mediaStorage
        if (!media.limits.enabled) { processIngestion(jobId, url); return }
        coroutineScope {
            val processing = async { processIngestion(jobId, url) }
            while (!processing.isCompleted) {
                media.checkImportBudget(jobId)
                delay(media.limits.checkIntervalMs)
            }
            processing.await()
        } // Scope cancellation reaps external processes before the worker removes files/releases its lease.
    }

    private suspend fun processIngestion(jobId: String, url: String) {
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.FETCHING_METADATA) }
        val info = ytdlpClient.getVideoInfo(url, jobId)
        val videoId = info.id?.takeIf { Regex("[A-Za-z0-9_-]+").matches(it) }
            ?: throw IngestionException("Invalid video ID")
        val limits = ytdlpClient.mediaStorage.limits
        if (info.isLive == true) throw IngestionException("Live streams are not supported")
        val duration = info.duration?.takeIf { it.isFinite() && it > 0 && it <= limits.maxDurationSeconds }
            ?: throw IngestionException("Video duration exceeds the configured limit or is unknown")
        val estimate = listOfNotNull(info.filesize, info.estimatedFilesize).filter { it > 0 }.maxOrNull()
        if (limits.maxImportBytes > 0 && estimate != null && estimate > limits.maxImportBytes) {
            throw MediaLimitException("Estimated media size exceeds configured import budget")
        }
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.DOWNLOADING_AUDIO) }
        val (audio, subtitlesFile) = ytdlpClient.downloadContent(url, videoId, jobId)
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.PARSING_SUBTITLES) }
        val subtitles = withContext(Dispatchers.Default) { SrtParser.parse(videoId, subtitlesFile.readText()) }
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.GENERATING_WAVEFORM) }
        val waveform = waveformExtractor.extract(audio, duration) { ytdlpClient.mediaStorage.checkImportBudget(jobId) }
        currentCoroutineContext().ensureActive()
        ytdlpClient.mediaStorage.checkImportBudget(jobId)
        ytdlpClient.mediaStorage.retainAudio(jobId, audio)
        val content = ImmersionContent(videoId, info.title ?: "Extracted Content",
            "/api/media/$jobId/$videoId.mp3", audio.absolutePath, url, duration.toLong(), subtitles, waveform)
        ytdlpClient.mediaStorage.publish(jobId) {
            jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.READY, content = content) }
        }
    }

    private fun failJob(jobId: String, message: String) {
        runCatching { ytdlpClient.discardJobFiles(jobId) }.onFailure { logger.error("Cannot clean import $jobId", it) }
        runCatching { jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.ERROR, error = message) } }
            .onFailure { logger.error("Cannot update import $jobId", it) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        ingestionChannel.close()
        lifetime.cancel()
        while (true) {
            val pending = ingestionChannel.tryReceive().getOrNull() ?: break
            failJob(pending.jobId, "Import interrupted")
            pending.lease.close()
            slots.release()
        }
    }
}
