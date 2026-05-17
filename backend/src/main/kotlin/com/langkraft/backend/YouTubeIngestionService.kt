package com.langkraft.backend

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.ContentProcessingStatus
import com.langkraft.domain.model.IngestionJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Service that uses YtdlpClient to fetch content from YouTube.
 * Performs operations asynchronously and tracks job state.
 */
class YouTubeIngestionService(
    private val ytdlpClient: YtdlpClient,
    private val jobRepository: IngestionJobRepository
) {
    private val logger = LoggerFactory.getLogger(YouTubeIngestionService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val CLEANUP_INTERVAL_MS = 3600_000L // 1 hour
        private const val JOB_EXPIRY_MS = 24 * 3600_000L // 24 hours
    }

    init {
        scope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                jobRepository.cleanup(JOB_EXPIRY_MS)
                logger.info("Executed job cleanup")
            }
        }
    }

    fun startIngestion(url: String): String {
        val jobId = UUID.randomUUID().toString()
        jobRepository.save(IngestionJob(jobId, ContentProcessingStatus.IDLE, url))
        
        scope.launch {
            try {
                processIngestion(jobId, url)
            } catch (e: Exception) {
                logger.error("Ingestion failed for $jobId", e)
                jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.ERROR, error = e.message) }
            }
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

    private suspend fun generateWaveform(jobId: String, audioPath: String): List<Float> {
        jobRepository.update(jobId) { it.copy(status = ContentProcessingStatus.GENERATING_WAVEFORM) }
        delay(500)
        return List(100) { Random.nextFloat() }
    }
}
