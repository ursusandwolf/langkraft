package com.langkraft.backend

import com.langkraft.domain.model.ImmersionContent
import com.sapher.youtubedl.YtdlpLauncher
import com.sapher.youtubedl.YtdlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Service that uses YtdlpJava to fetch content from YouTube.
 */
class YouTubeIngestionService(
    private val downloadsDir: String = "downloads"
) {
    init {
        File(downloadsDir).mkdirs()
    }

    suspend fun ingest(url: String): ImmersionContent = withContext(Dispatchers.IO) {
        val videoId = UUID.randomUUID().toString()
        val outputPath = "$downloadsDir/$videoId"
        
        // 1. Prepare Request using YtdlpJava (as per user's library style)
        val request = YtdlpRequest(url, downloadsDir)
            .setOption("write-auto-sub")
            .setOption("sub-lang", "de")
            .setOption("convert-subs", "srt")
            .setOption("extract-audio")
            .setOption("audio-format", "opus")
            .setOption("output", "$videoId.%(ext)s")

        // 2. Execute
        val response = YtdlpLauncher.execute(request)
        
        if (response.exitCode != 0) {
            throw Exception("yt-dlp failed: ${response.out}")
        }

        // 3. Find files
        val audioFile = File("$downloadsDir/$videoId.opus")
        val srtFile = File("$downloadsDir/$videoId.de.srt")
        
        if (!audioFile.exists() || !srtFile.exists()) {
            throw Exception("Required files (audio or subtitles) missing after download")
        }

        // 4. Parse Subtitles
        val subtitles = SrtParser.parse(videoId, srtFile.readText())

        // 5. Construct Result
        ImmersionContent(
            id = videoId,
            title = "Extracted Content", // In production, get this from ytdlp --get-title
            audioUrl = "/api/media/$videoId.mp3",
            localAudioPath = null,
            sourceUrl = url,
            durationSeconds = 0, // In production, get from ytdlp --get-duration
            subtitles = subtitles
        )
    }
}
