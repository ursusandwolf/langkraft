package com.langkraft.backend

import com.langkraft.domain.model.ImmersionContent
import com.sapher.youtubedl.YtdlpLauncher
import com.sapher.youtubedl.YtdlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Service that uses YtdlpJava to fetch content from YouTube.
 */
class YouTubeIngestionService(
    private val downloadsDir: String = "downloads"
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        File(downloadsDir).mkdirs()
    }

    suspend fun ingest(url: String): ImmersionContent = withContext(Dispatchers.IO) {
        // 1. Get info first to get real ID
        val infoRequest = YtdlpRequest(url, downloadsDir)
            .setOption("dump-json")
            .setOption("no-download")
        
        val infoResponse = YtdlpLauncher.execute(infoRequest)
        if (infoResponse.exitCode != 0) {
            throw Exception("Failed to get info: ${infoResponse.out}")
        }
        
        val info = json.decodeFromString<YtdlpInfo>(infoResponse.out)
        val videoId = info.id ?: throw Exception("Could not determine video ID")

        // 2. Prepare Request to download
        val request = YtdlpRequest(url, downloadsDir)
            .setOption("write-auto-sub")
            .setOption("sub-lang", "de")
            .setOption("convert-subs", "srt")
            .setOption("extract-audio")
            .setOption("audio-format", "opus")
            .setOption("output", "$videoId.%(ext)s")

        // 3. Execute download
        val response = YtdlpLauncher.execute(request)
        if (response.exitCode != 0) {
            throw Exception("yt-dlp failed: ${response.out}")
        }

        // 4. Find files
        val audioFile = File("$downloadsDir/$videoId.opus")
        val srtFile = File("$downloadsDir/$videoId.de.srt")
        
        if (!audioFile.exists() || !srtFile.exists()) {
            throw Exception("Required files (audio or subtitles) missing after download")
        }

        // 5. Parse Subtitles
        val subtitles = SrtParser.parse(videoId, srtFile.readText())

        // 6. Construct Result
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

    @Serializable
    private data class YtdlpInfo(
        val id: String? = null,
        val title: String? = null,
        val duration: Long? = null
    )
}
