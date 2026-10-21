package com.langkraft.data

import com.langkraft.data.audio.FileAudioStorage
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.remote.ContentApi
import com.langkraft.data.repository.SqlDelightContentRepository
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.usecase.IngestContentUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestionRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val content = ImmersionContent("id", "Title", "/api/media/id.opus",
        null, "https://youtu.be/video", 1,
        listOf(SubtitleLine("line", "id", 0, 1000, "Guten Tag", null)))

    @Test
    fun importedContentAudioAndWordSurviveReopeningTheDatabase() = runTest {
        val databaseFile = folder.newFile("library.db")
        val audioDirectory = File(folder.root, "audio")
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/ingest" -> respond(Json.encodeToString(content),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
                "/api/media/id.opus" -> respond("opus", headers = headersOf(HttpHeaders.ContentLength, "4"))
                else -> error("Unexpected request: ${request.url}")
            }
        })
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handle = createJvmDatabase(databaseFile)
        val imported: ImmersionContent
        try {
            val repository = SqlDelightContentRepository(handle.access,
                ContentApi(client, "https://backend"), FileAudioStorage(audioDirectory, dispatcher),
                { 100L }, dispatcher)
            imported = IngestContentUseCase(repository)(content.sourceUrl).getOrThrow()
            assertEquals("opus", File(imported.localAudioPath!!).readText())
            assertEquals(content.subtitles, imported.subtitles)
            SqlDelightVocabularyRepository(handle.access, { 100L }, dispatcher).saveWord(
                VocabularyWord("word", "Tag", null, "day", "Guten Tag", "id", "line"))
        } finally {
            handle.close()
            client.close()
        }
        val reopened = createJvmDatabase(databaseFile)
        val offlineClient = HttpClient(MockEngine { error("Reopening must not require a network") })
        try {
            val repository = SqlDelightContentRepository(reopened.access,
                ContentApi(offlineClient, "https://backend"), FileAudioStorage(audioDirectory, dispatcher),
                { 200L }, dispatcher)
            assertEquals(imported, repository.getContentById("id"))
            assertEquals(listOf(imported), repository.getAllContent().first())
            val words = SqlDelightVocabularyRepository(reopened.access, { 200L }, dispatcher)
            assertEquals("day", words.getWordsToReview().first().single().translation)
        } finally {
            reopened.close()
            offlineClient.close()
        }
    }

    @Test
    fun rejectedImportDoesNotSavePartialContentOrDownloadAudio() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            respond("""{"error":"Unable to import video"}""", HttpStatusCode.BadGateway)
        })
        val handle = createJvmDatabase(folder.newFile("library.db"))
        val audioDirectory = File(folder.root, "audio")
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = SqlDelightContentRepository(handle.access,
                ContentApi(client, "https://backend"), FileAudioStorage(audioDirectory, dispatcher),
                { 100L }, dispatcher)
            repository.saveContent(content)
            assertTrue(IngestContentUseCase(repository)("https://youtu.be/invalid-subtitles").isFailure)
            assertEquals(listOf("/api/ingest"), requests)
            assertEquals(listOf(content), repository.getAllContent().first())
            assertEquals(content, repository.getContentById(content.id))
            assertTrue(audioDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            handle.close()
            client.close()
        }
    }

    @Test
    fun failedAudioDownloadKeepsRemoteContentAndSubtitles() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/ingest") respond(Json.encodeToString(content))
            else respond("Not available", HttpStatusCode.ServiceUnavailable)
        })
        val handle = createJvmDatabase(folder.newFile("library.db"))
        val audioDirectory = File(folder.root, "audio")
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = SqlDelightContentRepository(handle.access,
                ContentApi(client, "https://backend"), FileAudioStorage(audioDirectory, dispatcher),
                { 100L }, dispatcher)
            assertTrue(IngestContentUseCase(repository)(content.sourceUrl).isFailure)
            val saved = repository.getContentById("id")!!
            assertNull(saved.localAudioPath)
            assertEquals("https://backend/api/media/id.opus", saved.audioUrl)
            assertEquals(content.subtitles, saved.subtitles)
            assertTrue(audioDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            handle.close()
            client.close()
        }
    }
}
