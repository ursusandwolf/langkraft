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
    @SerialName("webpage_url") val webpageUrl: String? = null,
    @SerialName("is_live") val isLive: Boolean? = null,
    val filesize: Long? = null,
    @SerialName("filesize_approx") val estimatedFilesize: Long? = null
)

/** Adapter to yt-dlp. Shell parsing and user configuration are disabled. */
class YtdlpClient(
    private val downloadsDir: String,
    private val runner: ProcessRunner = ExecutableProcessRunner(),
    val mediaStorage: BackendMediaStorage = BackendMediaStorage(downloadsDir)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val options = listOf("--ignore-config", "--no-playlist", "--quiet", "--no-warnings", "--no-progress", "--js-runtimes", "node")

    suspend fun getVideoInfo(url: String, jobId: String? = null): YtdlpInfo {
        SourceUrlValidator.validate(url)
        val arguments = options + listOf("--dump-single-json", "--skip-download", "--", url)
        val output = if (jobId == null) runner.execute(arguments)
        else runner.executeIn(arguments, mediaStorage.importDirectory(jobId)) { mediaStorage.checkImportBudget(jobId) }
        return json.decodeFromString<YtdlpInfo>(output)
    }

    suspend fun downloadContent(url: String, videoId: String, jobId: String): List<File> {
        SourceUrlValidator.validate(url)
        require(Regex("[A-Za-z0-9_-]+").matches(videoId)) { "Invalid video ID" }
        require(Regex("[A-Za-z0-9_-]+").matches(jobId)) { "Invalid job ID" }
        val directory = File(downloadsDir, jobId).absoluteFile
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create download directory" }
        runner.executeIn(options + listOf(
            "--write-subs", "--write-auto-subs", "--sub-langs", "de",
            "--sub-format", "srt/best", "--convert-subs", "srt",
            "--extract-audio", "--audio-format", "mp3",
            "--output", File(directory, "$videoId.%(ext)s").path, "--", url
        ), directory.toPath()) { mediaStorage.checkImportBudget(jobId) }
        val audio = File(directory, "$videoId.mp3")
        val subtitles = listOf(File(directory, "$videoId.de.srt"), File(directory, "$videoId.srt")).firstOrNull { it.isFile }
        if (!audio.isFile || audio.length() == 0L || subtitles == null) {
            throw IngestionException("Required audio or German subtitles are missing")
        }
        return listOf(audio, subtitles)
    }

    fun discardJobFiles(jobId: String) {
        mediaStorage.discardImport(jobId)
    }
}
