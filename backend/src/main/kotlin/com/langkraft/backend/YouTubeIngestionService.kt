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
        
        // 1. Prepare Request to get info and download
        val request = YtdlpRequest(url, downloadsDir)
            .setOption("write-auto-sub")
            .setOption("sub-lang", "de")
            .setOption("convert-subs", "srt")
            .setOption("extract-audio")
            .setOption("audio-format", "opus")
            .setOption("output", "$videoId.%(ext)s")
            .setOption("write-info-json")

        // 2. Execute
        val response = YtdlpLauncher.execute(request)
        
        if (response.exitCode != 0) {
            throw Exception("yt-dlp failed: ${response.out}")
        }

        // 3. Find files
        val audioFile = File("$downloadsDir/$videoId.opus")
        val srtFile = File("$downloadsDir/$videoId.de.srt")
        val infoFile = File("$downloadsDir/$videoId.info.json")
        
        if (!audioFile.exists() || !srtFile.exists()) {
            throw Exception("Required files (audio or subtitles) missing after download")
        }

        // 4. Parse Metadata (Very simple for now, should use a JSON parser if available)
        // For simplicity, we'll try to extract title from the info.json if it exists
        var title = "Extracted Content"
        var duration = 0L
        if (infoFile.exists()) {
            val infoJson = infoFile.readText()
            title = "\"title\": \"(.*?)\"".toRegex().find(infoJson)?.groupValues?.get(1) ?: title
            duration = "\"duration\": (\\d+)".toRegex().find(infoJson)?.groupValues?.get(1)?.toLong() ?: 0L
        }

        // 5. Parse Subtitles
        val subtitles = SrtParser.parse(videoId, srtFile.readText())

        // 6. Construct Result
        ImmersionContent(
            id = videoId,
            title = title,
            audioUrl = "/api/media/$videoId.opus",
            localAudioPath = audioFile.absolutePath,
            sourceUrl = url,
            durationSeconds = duration,
            subtitles = subtitles
        )
    }
}
