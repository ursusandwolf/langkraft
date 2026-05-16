package com.langkraft.backend

import com.sapher.youtubedl.YoutubeDL
import com.sapher.youtubedl.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class YtdlpInfo(
    val id: String? = null,
    val title: String? = null,
    val duration: Long? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null
)

/**
 * A dedicated client for interacting with the yt-dlp command line tool.
 */
class YtdlpClient(private val downloadsDir: String) {
    private val logger = LoggerFactory.getLogger(YtdlpClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        File(downloadsDir).mkdirs()
        YoutubeDL.setExecutablePath("yt-dlp")
    }

    suspend fun getVideoInfo(url: String): YtdlpInfo = withContext(Dispatchers.IO) {
        logger.info("Fetching video info for URL: $url")
        val request = YoutubeDLRequest(url, downloadsDir)
        request.setOption("dump-json")
        request.setOption("no-download")

        try {
            val response = YoutubeDL.execute(request)
            if (response.exitCode != 0) {
                logger.error("Failed to get info for $url. Exit code: ${response.exitCode}, Output: ${response.out}")
                throw IngestionException("Failed to get video info: ${response.out}")
            }

            json.decodeFromString<YtdlpInfo>(response.out)
        } catch (e: Exception) {
            if (e is IngestionException) throw e
            throw IngestionException("Error executing yt-dlp", e)
        }
    }

    suspend fun downloadContent(url: String, videoId: String): List<File> = withContext(Dispatchers.IO) {
        logger.info("Starting download for videoId: $videoId, URL: $url")
        val request = YoutubeDLRequest(url, downloadsDir)
        request.setOption("write-sub")
        request.setOption("sub-lang", "de")
        request.setOption("convert-subs", "srt")
        request.setOption("extract-audio")
        request.setOption("audio-format", "mp3")
        request.setOption("output", "$videoId.%(ext)s")

        try {
            val response = YoutubeDL.execute(request)
            if (response.exitCode != 0) {
                logger.error("Download failed for $videoId. Exit code: ${response.exitCode}, Output: ${response.out}")
                throw IngestionException("yt-dlp download failed: ${response.out}")
            }

            // Find downloaded files
            val downloadsFolder = File(downloadsDir)
            val audioFile = downloadsFolder.listFiles()?.find { it.name == "$videoId.mp3" }
            val srtFile = downloadsFolder.listFiles()?.find { it.name == "$videoId.de.srt" || it.name == "$videoId.srt" }

            if (audioFile == null || srtFile == null) {
                logger.error("Missing files after download. Audio: ${audioFile != null}, SRT: ${srtFile != null}")
                throw IngestionException("Required files missing after download for video ID: $videoId")
            }

            logger.info("Successfully downloaded audio and subtitles for $videoId")
            listOf(audioFile, srtFile)
        } catch (e: Exception) {
            if (e is IngestionException) throw e
            throw IngestionException("Error downloading content with yt-dlp", e)
        }
    }
}
