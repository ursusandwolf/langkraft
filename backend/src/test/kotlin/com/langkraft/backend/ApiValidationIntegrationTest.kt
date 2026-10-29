package com.langkraft.backend

import com.langkraft.domain.ai.*
import com.langkraft.domain.model.*
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class ApiValidationIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun productionRoutesValidateBodiesTextAndWholeSyncBatchBeforeWork() = testApplication {
        environment { config = MapApplicationConfig(
            "jwt.secret" to "test-secret-with-at-least-thirty-two-characters",
            "jwt.issuer" to "tests", "jwt.audience" to "tests", "jwt.realm" to "tests", "jwt.validity_ms" to "86400000",
            "storage.data_dir" to directory.resolve("backend").toString(),
            "storage.downloads_dir" to directory.resolve("downloads").toString(),
            "ingestion.cleanup_interval_ms" to "3600000", "ingestion.job_expiry_ms" to "86400000",
            "ingestion.worker_count" to "1", "ai.provider" to "none"
        ) }
        application { module() }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        for (path in listOf("register", "login")) {
            assertEquals(HttpStatusCode.PayloadTooLarge, jsonClient.post("/api/auth/$path") {
                contentType(ContentType.Application.Json); setBody("{invalid" + "x".repeat(ApiLimits.AUTH_BODY_BYTES))
            }.status)
        }
        val registered = jsonClient.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(RegisterRequest("a@example.org", "я".repeat(36), "A"))
        }
        assertEquals(HttpStatusCode.OK, registered.status)
        val token = registered.body<AuthResponse>().token
        assertEquals(HttpStatusCode.BadRequest, jsonClient.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(RegisterRequest("b@example.org", "я".repeat(37), "B"))
        }.status)

        // With AI disabled, valid input reaches the assistant (503), invalid input must still return 400.
        for (operation in listOf("translate-sentence", "analyze-sentence", "correct-text")) {
            for ((text, expected) in listOf("a".repeat(ApiLimits.AI_TEXT_CHARS) to HttpStatusCode.ServiceUnavailable,
                "a".repeat(ApiLimits.AI_TEXT_CHARS + 1) to HttpStatusCode.BadRequest, " " to HttpStatusCode.BadRequest)) {
                assertEquals(expected, jsonClient.post("/api/ai/$operation") {
                    bearerAuth(token); contentType(ContentType.Application.Json); setBody(TextRequest(text))
                }.status)
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, jsonClient.post("/api/ai/$operation") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("{" + "x".repeat(ApiLimits.AI_BODY_BYTES))
            }.status)
        }
        for ((word, context, expected) in listOf(
            Triple("w".repeat(ApiLimits.AI_WORD_CHARS), "context", HttpStatusCode.ServiceUnavailable),
            Triple("w".repeat(ApiLimits.AI_WORD_CHARS + 1), "context", HttpStatusCode.BadRequest),
            Triple("Haus", "a".repeat(ApiLimits.AI_TEXT_CHARS + 1), HttpStatusCode.BadRequest))) {
            assertEquals(expected, jsonClient.post("/api/ai/translate-word") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(TranslateRequest(word, context))
            }.status)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, jsonClient.post("/api/ai/translate-word") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("x".repeat(ApiLimits.AI_BODY_BYTES + 1))
        }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, jsonClient.post("/api/sync") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("x".repeat(ApiLimits.SYNC_BODY_BYTES + 1))
        }.status)
        val valid = SyncEntry(VocabularyWord("word", "Haus", contextSentence = "Das Haus", lastUpdated = 1), "UPSERT")
        val oversized = (0..ApiLimits.SYNC_BATCH_SIZE).map { valid.copy(word = valid.word.copy(id = "word-$it")) }
        for (request in listOf(SyncRequest(0, oversized), SyncRequest(-1, listOf(valid)),
            SyncRequest(0, listOf(valid, valid.copy(changeType = "INVALID"))),
            SyncRequest(0, listOf(valid, valid.copy(word = valid.word.copy(word = "")))))) {
            assertEquals(HttpStatusCode.BadRequest, jsonClient.post("/api/sync") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
            }.status)
        }
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("backend/langkraft.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM vocabulary_sync").use { rows ->
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1), "Invalid batches must not write their valid prefix")
                }
                statement.executeQuery("SELECT COUNT(*) FROM users").use { rows ->
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1), "Invalid auth must not create accounts")
                }
            }
        }
        assertEquals(HttpStatusCode.OK, jsonClient.post("/api/sync") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(SyncRequest(0, oversized.take(ApiLimits.SYNC_BATCH_SIZE)))
        }.status)
        assertEquals(HttpStatusCode.OK, jsonClient.post("/api/sync") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(SyncRequest(0, listOf(SyncEntry(VocabularyWord("word-0", "", contextSentence = "", lastUpdated = 2), "DELETE"))))
        }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, jsonClient.post("/api/ingest") {
            contentType(ContentType.Application.Json); setBody("x".repeat(ApiLimits.INGEST_BODY_BYTES + 1))
        }.status)
        assertEquals(HttpStatusCode.BadRequest, jsonClient.post("/api/ingest") {
            contentType(ContentType.Application.Json); setBody(IngestRequest("https://youtu.be/" + "a".repeat(2048)))
        }.status)
        jsonClient.close()
    }
}
