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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Opt-in: uses an already running local daemon and an already installed model. Never downloads. */
@EnabledIfEnvironmentVariable(named = "LANGKRAFT_OLLAMA_LIVE_MODEL", matches = ".+")
class OllamaLiveTest {
    @TempDir lateinit var directory: Path

    @Test fun jwtProtectedRoutesCompleteAllFourTasksAgainstLocalOllama() = testApplication {
        val model = System.getenv("LANGKRAFT_OLLAMA_LIVE_MODEL")
        environment { config = MapApplicationConfig(
            "jwt.secret" to "local-ollama-test-secret-at-least-thirty-two-characters",
            "jwt.issuer" to "ollama-test", "jwt.audience" to "ollama-test", "jwt.realm" to "test", "jwt.validity_ms" to "86400000",
            "storage.data_dir" to directory.resolve("backend").toString(), "storage.downloads_dir" to directory.resolve("downloads").toString(),
            "ingestion.cleanup_interval_ms" to "3600000", "ingestion.job_expiry_ms" to "86400000", "ingestion.worker_count" to "1",
            "ai.provider" to "ollama", "ai.model" to model, "ai.base_url" to "http://127.0.0.1:11434"
        ) }
        application { module() }
        val http = createClient { install(ContentNegotiation) { json() } }
        val registration = http.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("ollama@example.com", "local-test-password", "Local test"))
        }
        assertEquals(HttpStatusCode.OK, registration.status)
        val token = registration.body<AuthResponse>().token
        assertEquals("ollama", http.get("/api/ai/capabilities").body<AiCapabilities>().provider)
        for (operation in listOf("translate-word", "translate-sentence", "analyze-sentence", "correct-text")) {
            val started = System.nanoTime()
            val response = http.post("/api/ai/$operation") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                if (operation == "translate-word") setBody(TranslateRequest("Haus", "Das Haus ist groß."))
                else setBody(TextRequest(if (operation == "correct-text") "Ich gehen nach Hause." else "Das Haus ist groß."))
            }
            assertEquals(HttpStatusCode.OK, response.status, operation)
            when (operation) {
                "translate-word" -> {
                    val translation = response.body<TranslationResult>()
                    assertContains(translation.translation.lowercase(), "house")
                    assertContains(translation.lemma.lowercase(), "haus")
                }
                "translate-sentence" -> assertContains(response.body<SentenceTranslationResult>().translation.lowercase(), "house")
                "analyze-sentence" -> assertTrue(response.body<DeepAnalysisResult>().words.any { it.lemma.equals("Haus", ignoreCase = true) })
                else -> assertTrue(Regex("\\bgehe\\b", RegexOption.IGNORE_CASE).containsMatchIn(response.body<CorrectionResult>().correctedText))
            }
            println("Ollama smoke: model=$model operation=$operation elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
        http.close()
    }
}
