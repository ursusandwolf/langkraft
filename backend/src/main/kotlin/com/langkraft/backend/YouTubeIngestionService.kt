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
    private val ytdlpClient: YtdlpClient
) {
    private val logger = LoggerFactory.getLogger(YouTubeIngestionService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = ConcurrentHashMap<String, IngestionJob>()
    private val jobTimestamps = ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            while (true) {
                delay(3600_000) // Every hour
                cleanupOldJobs()
            }
        }
    }

    private fun cleanupOldJobs() {
        val now = System.currentTimeMillis()
        val expiryTime = 24 * 3600_000 // 24 hours
        jobTimestamps.forEach { (jobId, timestamp) ->
            if (now - timestamp > expiryTime) {
                jobs.remove(jobId)
                jobTimestamps.remove(jobId)
                logger.info("Cleaned up expired job: $jobId")
            }
        }
    }

    fun startIngestion(url: String): String {
        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        jobs[jobId] = IngestionJob(jobId, ContentProcessingStatus.IDLE, url)
        jobTimestamps[jobId] = now
        
        scope.launch {
            try {
                processIngestion(jobId, url)
            } catch (e: Exception) {
                logger.error("Ingestion failed for $jobId", e)
                updateJob(jobId) { it.copy(status = ContentProcessingStatus.ERROR, error = e.message) }
            }
        }
        return jobId
    }

    fun getJobStatus(jobId: String): IngestionJob? = jobs[jobId]

    private inline fun updateJob(jobId: String, crossinline modifier: (IngestionJob) -> IngestionJob) {
        jobs.computeIfPresent(jobId) { _, current -> 
            val updated = modifier(current)
            jobTimestamps[jobId] = System.currentTimeMillis()
            updated
        }
    }

    private suspend fun processIngestion(jobId: String, url: String) = withContext(Dispatchers.IO) {
        updateJob(jobId) { it.copy(status = ContentProcessingStatus.FETCHING_METADATA) }
        val info = ytdlpClient.getVideoInfo(url)
        val videoId = info.id ?: throw IngestionException("Could not determine video ID")

        updateJob(jobId) { it.copy(status = ContentProcessingStatus.DOWNLOADING_AUDIO) }
        val files = ytdlpClient.downloadContent(url, videoId)
        val audioFile = files.find { it.extension == "opus" } 
            ?: throw IngestionException("Audio file missing")
        val srtFile = files.find { it.extension == "srt" }
            ?: throw IngestionException("Subtitles file missing")

        updateJob(jobId) { it.copy(status = ContentProcessingStatus.PARSING_SUBTITLES) }
        val subtitles = SrtParser.parse(videoId, srtFile.readText())

        updateJob(jobId) { it.copy(status = ContentProcessingStatus.GENERATING_WAVEFORM) }
        val waveform = generateWaveform(audioFile.absolutePath)

        val content = ImmersionContent(
            id = videoId,
            title = info.title ?: "Extracted Content",
            audioUrl = "/api/media/$videoId.opus",
            localAudioPath = audioFile.absolutePath,
            sourceUrl = url,
            durationSeconds = info.duration ?: 0L,
            subtitles = subtitles,
            waveform = waveform
        )

        updateJob(jobId) { it.copy(status = ContentProcessingStatus.READY, content = content) }
    }
    
    private suspend fun generateWaveform(audioPath: String): List<Float> {
        delay(500)
        return List(100) { Random.nextFloat() }
    }
}
