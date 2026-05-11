package com.langkraft.backend

import com.sapher.youtubedl.YtdlpLauncher
import com.sapher.youtubedl.YtdlpRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class YtdlpInfo(
...
/**
 * A dedicated client for interacting with the yt-dlp command line tool.
 */
class YtdlpClient(private val downloadsDir: String) {
    private val logger = LoggerFactory.getLogger(YtdlpClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        File(downloadsDir).mkdirs()
    }

    fun getVideoInfo(url: String): YtdlpInfo {
        logger.info("Fetching video info for URL: $url")
        val request = YtdlpRequest(url, downloadsDir)
            .setOption("dump-json")
            .setOption("no-download")

        val response = YtdlpLauncher.execute(request)
        if (response.exitCode != 0) {
            logger.error("Failed to get info for $url. Exit code: ${response.exitCode}, Output: ${response.out}")
            throw RuntimeException("Failed to get video info: ${response.out}")
        }

        return json.decodeFromString<YtdlpInfo>(response.out)
    }

    fun downloadContent(url: String, videoId: String): List<File> {
        logger.info("Starting download for videoId: $videoId, URL: $url")
        val request = YtdlpRequest(url, downloadsDir)
            .setOption("write-auto-sub")
            .setOption("sub-lang", "de")
            .setOption("convert-subs", "srt")
            .setOption("extract-audio")
            .setOption("audio-format", "opus")
            .setOption("output", "$videoId.%(ext)s")

        val response = YtdlpLauncher.execute(request)
        if (response.exitCode != 0) {
            logger.error("Download failed for $videoId. Exit code: ${response.exitCode}, Output: ${response.out}")
            throw RuntimeException("yt-dlp download failed: ${response.out}")
        }

        val audioFile = File("$downloadsDir/$videoId.opus")
        val srtFile = File("$downloadsDir/$videoId.de.srt")

        if (!audioFile.exists() || !srtFile.exists()) {
            logger.error("Missing files after download. Audio: ${audioFile.exists()}, SRT: ${srtFile.exists()}")
            throw RuntimeException("Required files missing after download for video ID: $videoId")
        }

        logger.info("Successfully downloaded audio and subtitles for $videoId")
        return listOf(audioFile, srtFile)
    }
}

        return listOf(audioFile, srtFile)
    }
}
