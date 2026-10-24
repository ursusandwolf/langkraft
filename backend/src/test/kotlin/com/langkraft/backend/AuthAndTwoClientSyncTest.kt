package com.langkraft.backend

import com.langkraft.domain.model.*
import com.langkraft.domain.ai.*
import com.langkraft.data.ai.RemoteLinguisticAssistant
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.data.repository.SqlDelightContentRepository
import app.cash.sqldelight.async.coroutines.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AuthAndTwoClientSyncTest {
    @TempDir lateinit var directory: Path

    @Test fun realAuthAndTwoLocalDatabasesPreserveSrsAndPropagateDeletion() = testApplication {
        val media = directory.resolve("downloads/test.mp3").toFile()
        media.parentFile.mkdirs()
        media.writeBytes(ByteArray(100) { it.toByte() })
        environment { config = MapApplicationConfig(
            "jwt.secret" to "test-secret-with-at-least-thirty-two-characters",
            "jwt.issuer" to "langkraft-tests", "jwt.audience" to "langkraft-tests", "jwt.realm" to "test", "jwt.validity_ms" to "86400000",
            "storage.data_dir" to directory.resolve("backend").toString(), "storage.downloads_dir" to directory.resolve("downloads").toString(),
            "ingestion.cleanup_interval_ms" to "3600000", "ingestion.job_expiry_ms" to "86400000", "ingestion.worker_count" to "1",
            "ai.provider" to "none"
        ) }
        application { module() }
        val auth = createClient { install(ContentNegotiation) { json() } }
        val range = auth.get("/api/media/test.mp3") { header(HttpHeaders.Range, "bytes=0-9") }
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertEquals(10, range.body<ByteArray>().size)
        assertEquals(HttpStatusCode.Unauthorized, auth.post("/api/sync") {
            contentType(ContentType.Application.Json); setBody(SyncRequest(0, emptyList()))
        }.status)
        val register = auth.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(RegisterRequest("test@example.com", "password-for-test", "Test"))
        }
        assertEquals(HttpStatusCode.OK, register.status)
        val token = register.body<AuthResponse>().token
        assertEquals(HttpStatusCode.Unauthorized, auth.post("/api/auth/login") {
            contentType(ContentType.Application.Json); setBody(AuthRequest("test@example.com", "wrong"))
        }.status)
        assertEquals(HttpStatusCode.OK, auth.post("/api/auth/login") {
            contentType(ContentType.Application.Json); setBody(AuthRequest("test@example.com", "password-for-test"))
        }.status)
        val firstClient = createClient { install(ContentNegotiation) { json() }; defaultRequest { bearerAuth(token) } }
        val secondClient = createClient { install(ContentNegotiation) { json() }; defaultRequest { bearerAuth(token) } }
        // Exercise the production adapter against real JWT-protected Ktor routes.
        for (operation in listOf("translate-word", "translate-sentence", "analyze-sentence", "correct-text")) {
            assertEquals(HttpStatusCode.Unauthorized, auth.post("/api/ai/$operation") {
                contentType(ContentType.Application.Json)
                if (operation == "translate-word") setBody(TranslateRequest("Haus", "Das Haus"))
                else setBody(TextRequest("Das Haus"))
            }.status)
        }
        val ai = RemoteLinguisticAssistant(firstClient, Url("http://localhost"))
        assertFalse(ai.capabilities().enabled)
        assertFailsWith<IllegalStateException> { ai.translateWord("Haus", "Das Haus") }
        assertFailsWith<IllegalStateException> { ai.translateSentence("Das Haus") }
        assertFailsWith<IllegalStateException> { ai.analyzeSentence("Das Haus") }
        assertFailsWith<IllegalStateException> { ai.correctText("Das Haus") }
        val first = createJvmDatabase(directory.resolve("first.db").toFile())
        val second = createJvmDatabase(directory.resolve("second.db").toFile())
        try {
            val one = SqlDelightVocabularyRepository(first.access, firstClient, Url("http://localhost"))
            val two = SqlDelightVocabularyRepository(second.access, secondClient, Url("http://localhost"))
            SqlDelightContentRepository(first.access).saveContent(ImmersionContent("video", "Title", "/video.mp3", null,
                "https://youtu.be/video", 1, listOf(SubtitleLine("cue", "video", 0, 1000, "Das Haus", null))))
            val word = VocabularyWord("word", "Haus", contextSentence="Das Haus", intervalDays=7, easeFactor=2.7,
                lapseCount=3, tags=listOf("a,b", "tag"), nextReviewMs=5000, status=WordStatus.LEARNING, contentId="video", subtitleLineId="cue")
            one.saveWord(word)
            val firstCursor = one.sync(0)
            val secondCursor = two.sync(0)
            val row = second.database.appDatabaseQueries.selectWordById("word").awaitAsOne()
            assertEquals(7, row.intervalDays)
            assertEquals(3, row.lapseCount)
            assertEquals("[\"a,b\",\"tag\"]", row.tags)
            assertNull(row.contentId)
            assertEquals("[\"video\",\"cue\"]", second.database.appDatabaseQueries.getSyncMetadata("source_refs:word").awaitAsOne())
            two.saveWord(word.copy(intervalDays=14, contentId=null, subtitleLineId=null))
            two.sync(secondCursor)
            val afterUpdate = one.sync(firstCursor)
            assertEquals(14, first.database.appDatabaseQueries.selectWordById("word").awaitAsOne().intervalDays)
            assertEquals("cue", first.database.appDatabaseQueries.selectWordById("word").awaitAsOne().subtitleLineId)
            two.deleteWord("word")
            two.sync(0)
            one.sync(afterUpdate)
            assertNull(first.database.appDatabaseQueries.selectWordById("word").awaitAsOneOrNull())
            assertTrue(first.database.appDatabaseQueries.getAllPendingChanges().awaitAsList().isEmpty())
        } finally { first.close(); second.close(); firstClient.close(); secondClient.close(); auth.close() }
    }
}
