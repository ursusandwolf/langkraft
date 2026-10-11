package com.langkraft.backend

import com.langkraft.domain.model.ImmersionContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

fun interface ContentIngestion { suspend fun ingest(url: String): ImmersionContent }
fun interface MediaExtractor { suspend fun extract(url: String, outputDirectory: File) }

class YtDlpExtractor(private val executable: String = "yt-dlp", private val timeoutSeconds: Long = 180) : MediaExtractor {
    override suspend fun extract(url: String, outputDirectory: File) {
        runInterruptible(Dispatchers.IO) {
            val process = ProcessBuilder(
                executable, "--no-playlist", "--no-progress", "--no-warnings",
                "--write-auto-subs", "--write-subs", "--sub-langs", "de",
                "--convert-subs", "srt", "--extract-audio", "--audio-format", "opus",
                "--write-info-json", "--socket-timeout", "30", "--retries", "2",
                "--max-filesize", "100M", "--output", "audio.%(ext)s", "--", url,
            ).directory(outputDirectory).redirectErrorStream(true)
                .redirectOutput(File(outputDirectory, "extract.log")).start()
            try {
                check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "YouTube extraction timed out" }
                check(process.exitValue() == 0) { "YouTube extraction failed; check yt-dlp and FFmpeg installation" }
            } finally {
                process.descendants().forEach { if (it.isAlive) it.destroyForcibly() }
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}

class YouTubeIngestionService(
    private val downloadsDir: File = File("downloads"),
    private val extractor: MediaExtractor = YtDlpExtractor(),
) : ContentIngestion {
    private val slots = Semaphore(2)
    override suspend fun ingest(url: String): ImmersionContent {
        validateYouTubeUrl(url)
        return slots.withPermit {
            val id = UUID.randomUUID().toString()
            val directory = File(downloadsDir, id)
            withContext(Dispatchers.IO) { check(directory.mkdirs()) { "Cannot create media directory" } }
            try {
                extractor.extract(url, directory)
                withContext(Dispatchers.IO) {
                    val audio = File(directory, "audio.opus")
                    val srt = File(directory, "audio.de.srt")
                    val info = File(directory, "audio.info.json")
                    check(audio.isFile && audio.length() > 0 && srt.isFile && info.isFile) {
                        "This video must provide German subtitles and downloadable audio"
                    }
                    val metadata = Json.parseToJsonElement(info.readText()).jsonObject
                    val subtitles = SrtParser.parse(id, srt.readText(Charsets.UTF_8))
                    check(subtitles.isNotEmpty()) { "German subtitles are empty" }
                    val title = metadata["title"]?.jsonPrimitive?.content ?: error("Video title is missing")
                    val duration = metadata["duration"]?.jsonPrimitive?.doubleOrNull ?: error("Video duration is missing")
                    check(duration.isFinite() && duration > 0) { "Video duration is invalid" }
                    ImmersionContent(id, title, "/api/media/$id.opus", null, url, duration.toLong(), subtitles)
                }
            } catch (failure: Exception) {
                // Remove only the UUID directory created by this request.
                withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
                if (failure is CancellationException) throw failure
                throw failure
            }
        }
    }
}

fun validateYouTubeUrl(url: String) {
    val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("Invalid YouTube URL") }
    require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "Use an HTTPS YouTube URL" }
    require(uri.host?.lowercase() in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")) { "Only YouTube URLs are supported" }
    require(uri.path == "/watch" || uri.path?.startsWith("/shorts/") == true ||
        uri.path?.startsWith("/live/") == true || (uri.host == "youtu.be" && !uri.path.isNullOrBlank() && uri.path != "/")) {
        "Use a link to a single YouTube video"
    }
    if (uri.path == "/watch") require(uri.rawQuery?.split("&")?.any { it.startsWith("v=") && it.length > 2 } == true) {
        "YouTube URL is missing a video ID"
    }
}