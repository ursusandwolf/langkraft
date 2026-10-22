package com.langkraft.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class YtdlpInfo(
    val id: String? = null,
    val title: String? = null,
    val duration: Double? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null
)

/** Adapter to yt-dlp. Shell parsing and user configuration are disabled. */
class YtdlpClient(
    private val downloadsDir: String,
    private val runner: ProcessRunner = ExecutableProcessRunner()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val options = listOf("--ignore-config", "--no-playlist", "--quiet", "--no-warnings", "--no-progress", "--js-runtimes", "node")

    suspend fun getVideoInfo(url: String): YtdlpInfo {
        SourceUrlValidator.validate(url)
        val output = runner.execute(options + listOf("--dump-single-json", "--skip-download", "--", url))
        return json.decodeFromString<YtdlpInfo>(output)
    }

    suspend fun downloadContent(url: String, videoId: String, jobId: String): List<File> {
        SourceUrlValidator.validate(url)
        require(Regex("[A-Za-z0-9_-]+").matches(videoId)) { "Invalid video ID" }
        require(Regex("[A-Za-z0-9_-]+").matches(jobId)) { "Invalid job ID" }
        val directory = File(downloadsDir, jobId).absoluteFile
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create download directory" }
        runner.execute(options + listOf(
            "--write-subs", "--write-auto-subs", "--sub-langs", "de",
            "--sub-format", "srt/best", "--convert-subs", "srt",
            "--extract-audio", "--audio-format", "mp3",
            "--output", File(directory, "$videoId.%(ext)s").path, "--", url
        ))
        val audio = File(directory, "$videoId.mp3")
        val subtitles = listOf(File(directory, "$videoId.de.srt"), File(directory, "$videoId.srt")).firstOrNull { it.isFile }
        if (!audio.isFile || audio.length() == 0L || subtitles == null) {
            throw IngestionException("Required audio or German subtitles are missing")
        }
        return listOf(audio, subtitles)
    }

    fun discardJobFiles(jobId: String) {
        require(Regex("[A-Za-z0-9_-]+").matches(jobId))
        File(downloadsDir, jobId).deleteRecursively()
    }
}
