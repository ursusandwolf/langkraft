package com.langkraft.backend

import com.langkraft.domain.model.ImmersionContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Service that uses YtdlpClient to fetch content from YouTube.
 */
class YouTubeIngestionService(
    private val ytdlpClient: YtdlpClient
) {
    private val logger = LoggerFactory.getLogger(YouTubeIngestionService::class.java)

    suspend fun ingest(url: String): ImmersionContent = withContext(Dispatchers.IO) {
        logger.info("Starting ingestion process for URL: $url")
        // 1. Get info
        val info = ytdlpClient.getVideoInfo(url)
        val videoId = info.id ?: throw IngestionException("Could not determine video ID")

        // 2. Download files
        logger.info("Video ID determined: $videoId. Downloading content...")
        val files = ytdlpClient.downloadContent(url, videoId)
        val audioFile = files.find { it.extension == "opus" } 
            ?: throw IngestionException("Audio file missing")
        val srtFile = files.find { it.extension == "srt" }
            ?: throw IngestionException("Subtitles file missing")

        // 3. Parse Subtitles
        logger.info("Parsing subtitles for $videoId...")
        val subtitles = SrtParser.parse(videoId, srtFile.readText())
        logger.info("Parsed ${subtitles.size} subtitle lines.")

        // 4. Construct Result
        ImmersionContent(
            id = videoId,
            title = info.title ?: "Extracted Content",
            audioUrl = "/api/media/$videoId.opus",
            localAudioPath = audioFile.absolutePath,
            sourceUrl = url,
            durationSeconds = info.duration ?: 0L,
            subtitles = subtitles
        )
    }
}
