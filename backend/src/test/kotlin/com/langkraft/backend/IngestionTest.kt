package com.langkraft.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestionTest {
    @get:Rule
    val directory = TemporaryFolder()

    private fun service(runner: YtDlpRunner = successfulDownload()) = YouTubeIngestionService(
        directory.root.path, "https://media.example", runner
    )

    private fun successfulDownload(subtitles: String = "1\n00:00:00,000 --> 00:00:01,000\nGuten Tag\n") = YtDlpRunner { arguments ->
        assertTrue("--no-playlist" in arguments)
        assertEquals("node", arguments[arguments.indexOf("--js-runtimes") + 1])
        val output = arguments[arguments.indexOf("--output") + 1]
        File(output.replace("%(ext)s", "opus")).writeText("audio-data")
        File(output.replace("%(ext)s", "de.srt")).writeText(subtitles)
        File(output.replace("%(ext)s", "info.json")).writeText("""{"title":"Test video","duration":12.5}""")
    }

    @Test
    fun importedAudioUrlNamesAnExistingOpusFile() = runTest {
        val service = service()
        val content = service.ingest("https://youtu.be/video")
        assertEquals("Test video", content.title)
        assertEquals(12L, content.durationSeconds)
        assertEquals("https://media.example/api/media/${content.id}.opus", content.audioUrl)
        assertNotNull(service.mediaFile("${content.id}.opus"))
        assertEquals("Guten Tag", content.subtitles.single().textDe)
        assertEquals(content.id, content.subtitles.single().contentId)
        assertNull(service.mediaFile("../secret.opus"))
    }

    @Test
    fun rejectsNonYouTubeUrlsBeforeInvokingDownloader() = runTest {
        val service = service(YtDlpRunner { error("Downloader must not run") })
        for (url in listOf("file:///etc/passwd", "https://youtube.com.evil.test/watch",
            "https://youtube.com@localhost/watch", "http://youtu.be/video",
            "https://www.youtube.com/playlist?list=videos", "https://www.youtube.com/channel/example")) {
            assertFailsWith<InvalidSourceUrlException> { service.ingest(url) }
        }
    }

    @Test
    fun missingSubtitlesFailsAndRemovesPartialFiles() = runTest {
        val service = service(YtDlpRunner { args ->
            val output = args[args.indexOf("--output") + 1]
            File(output.replace("%(ext)s", "opus")).writeText("partial")
        })
        assertFailsWith<IngestionException> { service.ingest("https://youtu.be/video") }
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun invalidSubtitlesRemoveOnlyTheFailedImportsFiles() = runTest {
        val existing = service().ingest("https://youtu.be/existing")
        val existingFiles = directory.root.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
        for (subtitles in listOf("", "\uFEFF\n \n", "1", "1\n00:00:01,000 --> 00:00:02,000\nHallo\n\n2")) {
            val failure = assertFailsWith<IngestionException> {
                service(successfulDownload(subtitles)).ingest("https://youtu.be/invalid")
            }
            // Coroutine stack-trace recovery may wrap the original exception.
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is SubtitleParseException },
                failure.stackTraceToString())
            assertEquals(existingFiles, directory.root.listFiles().orEmpty().associate { it.name to it.readBytes().toList() })
            assertNotNull(service().mediaFile("${existing.id}.opus"))
        }
    }

    @Test
    fun otherLanguagesAndUnconvertedVttDoNotReplaceGermanSrt() = runTest {
        for (extension in listOf("en.srt", "de.vtt")) {
            val service = service(YtDlpRunner { args ->
                successfulDownload().execute(args)
                val output = args[args.indexOf("--output") + 1]
                val german = File(output.replace("%(ext)s", "de.srt"))
                assertTrue(german.renameTo(File(output.replace("%(ext)s", extension))))
            })
            assertFailsWith<IngestionException> { service.ingest("https://youtu.be/video") }
            assertTrue(directory.root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun requestsVttConversionAndParsesTheConvertedGermanFixture() = runTest {
        // Boundary fixture: yt-dlp/FFmpeg produces SRT before the service reads it.
        // Real offline FFmpeg conversion is repeatable using docs/SUBTITLES.md.
        val converted = javaClass.getResource("/subtitles/german.srt")!!.readText()
        val service = service(YtDlpRunner { args ->
            assertEquals("de", args[args.indexOf("--sub-langs") + 1])
            assertEquals("srt/best", args[args.indexOf("--sub-format") + 1])
            assertEquals("srt", args[args.indexOf("--convert-subs") + 1])
            successfulDownload(converted).execute(args)
        })
        val imported = service.ingest("https://youtu.be/video")
        assertEquals(listOf("Guten Tag zusammen!", "Wie geht es dir?"), imported.subtitles.map { it.textDe })
        assertEquals(listOf(1001L, 2500L), imported.subtitles.map { it.startMs })
        assertEquals(listOf(3500L, 4000L), imported.subtitles.map { it.endMs })
    }

    @Test
    fun httpMalformedSubtitlesReturnAnErrorWithoutPartialContent() = testApplication {
        application { module(service(successfulDownload("1\n00:00:00,000 --> 00:00:01,000\nHallo\n\n2"))) }
        val response = client.post("/api/ingest") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://youtu.be/video"}""")
        }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals(setOf("error"), Json.parseToJsonElement(response.bodyAsText()).jsonObject.keys)
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationIsPropagatedAndRemovesPartialFiles() = runTest {
        val service = service(YtDlpRunner { args ->
            val output = args[args.indexOf("--output") + 1]
            File(output.replace("%(ext)s", "opus.part")).writeText("partial")
            throw CancellationException("Cancelled")
        })
        assertFailsWith<CancellationException> { service.ingest("https://youtu.be/video") }
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun httpImportReturnsPlayableMediaAndSupportsRangeRequests() = testApplication {
        application { module(service()) }
        val response = client.post("/api/ingest") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://youtu.be/video"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val url = Json.parseToJsonElement(response.bodyAsText()).jsonObject["audioUrl"]!!.jsonPrimitive.content
        val path = url.removePrefix("https://media.example")
        val media = client.get(path)
        assertEquals(HttpStatusCode.OK, media.status)
        assertEquals("audio-data", media.bodyAsText())
        val range = client.get(path) { header(HttpHeaders.Range, "bytes=0-4") }
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertEquals("audio", range.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/media/invalid.opus").status)
    }

    @Test
    fun httpImportReportsBadInputAndDownloadFailure() = testApplication {
        application { module(service(YtDlpRunner { throw IngestionException("Failed") })) }
        suspend fun import(url: String) = client.post("/api/ingest") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"$url"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, import("https://example.com/video").status)
        assertEquals(HttpStatusCode.BadGateway, import("https://youtu.be/video").status)
    }
}
