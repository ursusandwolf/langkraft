package com.langkraft.backend

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.ContentProcessingStatus
import com.langkraft.domain.model.IngestionJob
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random

/**
 * Service that uses YtdlpClient to fetch content from YouTube.
 * Performs operations asynchronously via a work queue and tracks job state.
 */
class YouTubeIngestionService(
    private val ytdlpClient: YtdlpClient,
    private val jobRepository: IngestionJobRepository,
    config: ApplicationConfig,
    private val externalScope: CoroutineScope? = null
) {
    private val logger = LoggerFactory.getLogger(YouTubeIngestionService::class.java)
    private val scope = externalScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ingestionChannel = Channel<Pair<String, String>>(Channel.UNLIMITED)

    private val cleanupIntervalMs = config.property("ingestion.cleanup_interval_ms").getString().toLong()
    private val jobExpiryMs = config.property("ingestion.job_expiry_ms").getString().toLong()
    private val workerCount = config.property("ingestion.worker_count").getString().toInt()

    init {
        startCleanupLoop()
        startWorkerRoutines()
    }
    
    private fun startWorkerRoutines() {
        repeat(workerCount) {
            scope.launch {
                for ((jobId, url) in ingestionChannel) {
                    try {
                        processIngestion(jobId, url)
                    } catch (e: Exception) {
                        logger.error("Ingestion failed for $jobId", e)
                        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.ERROR, error = e.message) }
                    }
                }
            }
        }
    }

    private fun startCleanupLoop() {
        scope.launch {
            while (true) {
                delay(cleanupIntervalMs)
                try {
                    jobRepository.cleanup(jobExpiryMs)
                    logger.info("Executed job cleanup")
                } catch (e: Exception) {
                    logger.error("Job cleanup failed", e)
                }
            }
        }
    }

    fun startIngestion(url: String): String {
        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val job = IngestionJob(
            jobId = jobId,
            status = ContentProcessingStatus.IDLE,
            url = url,
            createdAt = now,
            lastUpdatedAt = now
        )
        jobRepository.save(job)
        
        scope.launch {
            ingestionChannel.send(jobId to url)
        }
        
        return jobId
    }

    fun getJobStatus(jobId: String): IngestionJob? = jobRepository.get(jobId)

    private suspend fun processIngestion(jobId: String, url: String) = withContext(Dispatchers.IO) {
        val info = fetchMetadata(jobId, url)
        val videoId = info.id ?: throw IngestionException("Could not determine video ID")

        val (audioFile, srtFile) = downloadContent(jobId, url, videoId)

        val subtitles = parseSubtitles(jobId, videoId, srtFile)

        val waveform = generateWaveform(jobId, audioFile.absolutePath)

        val content = ImmersionContent(
            id = videoId,
            title = info.title ?: "Extracted Content",
            audioUrl = "/api/media/$videoId.mp3",
            localAudioPath = audioFile.absolutePath,
            sourceUrl = url,
            durationSeconds = info.duration ?: 0L,
            subtitles = subtitles,
            waveform = waveform
        )

        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.READY, content = content) }
    }

    private suspend fun fetchMetadata(jobId: String, url: String) = withContext(Dispatchers.IO) {
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.FETCHING_METADATA) }
        ytdlpClient.getVideoInfo(url)
    }

    private suspend fun downloadContent(jobId: String, url: String, videoId: String) = withContext(Dispatchers.IO) {
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.DOWNLOADING_AUDIO) }
        val files = ytdlpClient.downloadContent(url, videoId)
        val audio = files.find { it.extension == "mp3" } ?: throw IngestionException("Audio missing")
        val srt = files.find { it.extension == "srt" } ?: throw IngestionException("Subtitles missing")
        Pair(audio, srt)
    }

    private suspend fun parseSubtitles(jobId: String, videoId: String, srtFile: java.io.File) = withContext(Dispatchers.Default) {
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.PARSING_SUBTITLES) }
        SrtParser.parse(videoId, srtFile.readText())
    }

    private suspend fun generateWaveform(jobId: String, audioPath: String): List<Float> = withContext(Dispatchers.IO) {
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.GENERATING_WAVEFORM) }
        
        val points = 200
        val sampleRate = 4000
        val waveform = mutableListOf<Float>()
        
        try {
            val process = ProcessBuilder(
                "ffmpeg", "-i", audioPath,
                "-ac", "1",
                "-ar", sampleRate.toString(),
                "-f", "s16le",
                "-"
            ).start()

            val inputStream = process.inputStream
            val buffer = ByteArray(4096)
            val allSamples = mutableListOf<Short>()

            // Read all samples into memory (for short audio files this is fine)
            // For very long files, we should aggregate on the fly.
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead step 2) {
                    if (i + 1 < bytesRead) {
                        val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                        allSamples.add(sample)
                    }
                }
            }
            process.waitFor()

            if (allSamples.isEmpty()) return@withContext List(points) { 0f }

            val chunkSize = (allSamples.size / points).coerceAtLeast(1)
            var maxVal = 0f

            for (i in 0 until points) {
                val start = i * chunkSize
                val end = ((i + 1) * chunkSize).coerceAtMost(allSamples.size)
                if (start >= allSamples.size) break

                var chunkMax = 0
                for (j in start until end) {
                    val absSample = Math.abs(allSamples[j].toInt())
                    if (absSample > chunkMax) chunkMax = absSample
                }
                
                val value = chunkMax.toFloat()
                waveform.add(value)
                if (value > maxVal) maxVal = value
            }

            // Normalize
            val result = if (maxVal > 0) {
                waveform.map { it / maxVal }
            } else {
                waveform
            }
            
            // Pad if needed
            val padded = result.toMutableList()
            while (padded.size < points) padded.add(0f)
            
            padded
        } catch (e: Exception) {
            logger.error("Failed to generate waveform for $audioPath", e)
            List(points) { 0.1f } // Fallback
        }
    }
}
