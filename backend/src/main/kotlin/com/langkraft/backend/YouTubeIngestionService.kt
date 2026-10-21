package com.langkraft.backend

import com.langkraft.domain.model.ImmersionContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlin.coroutines.coroutineContext

class InvalidSourceUrlException : IllegalArgumentException("Enter an HTTPS YouTube video URL")
class IngestionException(message: String, cause: Throwable? = null) : IOException(message, cause)

class YouTubeIngestionService(
    downloadsDir: String = "downloads",
    private val mediaBaseUrl: String = "http://localhost:8080",
    private val runner: YtDlpRunner = ProcessYtDlpRunner()
) {
    private val directory = File(downloadsDir).absoluteFile.apply {
        check(isDirectory || mkdirs()) { "Cannot create downloads directory" }
    }

    suspend fun ingest(url: String): ImmersionContent = withContext(Dispatchers.IO) {
        validateSourceUrl(url)
        val videoId = UUID.randomUUID().toString()
        try {
            runner.execute(listOf(
                "--ignore-config", "--no-playlist",
                "--js-runtimes", "node",
                "--write-subs", "--write-auto-subs", "--sub-langs", "de",
                "--sub-format", "srt/best", "--convert-subs", "srt",
                "--extract-audio", "--audio-format", "opus", "--write-info-json",
                "--output", File(directory, "$videoId.%(ext)s").path,
                "--", url
            ))
            coroutineContext.ensureActive()
            val audio = File(directory, "$videoId.opus")
            val srt = File(directory, "$videoId.de.srt")
            val info = File(directory, "$videoId.info.json")
            if (!audio.isFile || !srt.isFile || !info.isFile) {
                throw IngestionException("Audio, German subtitles or metadata are missing")
            }
            val subtitles = SrtParser.parse(videoId, srt.readText())
            val metadata = Json.parseToJsonElement(info.readText()).jsonObject
            ImmersionContent(
                id = videoId,
                title = metadata["title"]?.jsonPrimitive?.content ?: "YouTube video",
                audioUrl = "${mediaBaseUrl.trimEnd('/')}/api/media/$videoId.opus",
                localAudioPath = null,
                sourceUrl = url,
                durationSeconds = metadata["duration"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0,
                subtitles = subtitles
            )
        } catch (exception: CancellationException) {
            deleteFiles(videoId)
            throw exception
        } catch (exception: Exception) {
            deleteFiles(videoId)
            if (exception is IngestionException) throw exception
            if (exception is SubtitleParseException) throw IngestionException("Invalid German subtitles: ${exception.message}", exception)
            throw IngestionException("Unable to import YouTube content", exception)
        }
    }

    fun mediaFile(fileName: String): File? {
        if (!Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.opus").matches(fileName)) return null
        return File(directory, fileName).takeIf { it.isFile }
    }

    private fun deleteFiles(id: String) {
        directory.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
    }

    private fun validateSourceUrl(url: String) {
        val uri = try { URI(url) } catch (_: Exception) { throw InvalidSourceUrlException() }
        if (uri.scheme != "https" || uri.userInfo != null ||
            uri.host?.lowercase() !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be") ||
            uri.port !in listOf(-1, 443)
        ) throw InvalidSourceUrlException()
        val path = uri.path.orEmpty().trim('/')
        val isVideo = if (uri.host.lowercase() == "youtu.be") {
            path.isNotBlank() && '/' !in path
        } else {
            (path == "watch" && Regex("(^|&)v=[^&]+").containsMatchIn(uri.rawQuery.orEmpty())) ||
                Regex("(shorts|live|embed)/[^/]+").matches(path)
        }
        if (!isVideo) throw InvalidSourceUrlException()
    }
}
