package com.langkraft.backend

import com.langkraft.backend.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AiStrategyTest {
    @Test fun disabledIsDefaultEvenWhenAnUnusedKeyExists(): Unit = runBlocking {
        val settings = AiSettings.load(MapApplicationConfig()) { if (it == "GEMINI_API_KEY") "unused" else null }
        val client = HttpClient(MockEngine { error("Disabled mode must not call any provider") })
        try {
            val assistant = createLinguisticAssistant(settings, client)
            assertFalse(assistant.capabilities().enabled)
            assertFailsWith<AiUnavailableException> { assistant.translateWord("Haus", "Das Haus") }
        } finally { client.close() }
    }

    @Test fun invalidProviderAndIncompleteSettingsFailClearly() {
        assertFailsWith<IllegalArgumentException> { AiSettings.load(MapApplicationConfig("ai.provider" to "unknown")) { null } }
        assertFailsWith<IllegalArgumentException> { AiSettings.load(MapApplicationConfig("ai.provider" to "claude")) { null } }
        assertFailsWith<IllegalArgumentException> {
            AiSettings.load(MapApplicationConfig("ai.provider" to "claude", "ai.api_key" to "secret")) { null }
        }
    }

    @Test fun eachStrategySendsItsProtocolAndSharesTheSameLanguageTask(): Unit = runBlocking {
        for (provider in listOf("gemini", "claude", "openai")) {
            var calls = 0
            val engine = MockEngine { request ->
                calls++
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val text = """{"translation":"house","lemma":"Haus","partOfSpeech":"noun"}"""
                val payload = when (provider) {
                    "gemini" -> {
                        assertEquals("test-key", request.headers["x-goog-api-key"])
                        assertEquals("/v1/models/test-model:generateContent", request.url.encodedPath)
                        assertTrue(body["contents"].toString().contains("Das Haus"))
                        buildJsonObject { putJsonArray("candidates") { addJsonObject { putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } } } } }
                    }
                    "claude" -> {
                        assertEquals("test-key", request.headers["x-api-key"])
                        assertEquals("2023-06-01", request.headers["anthropic-version"])
                        assertEquals("/v1/messages", request.url.encodedPath)
                        assertEquals(4096, body["max_tokens"]?.jsonPrimitive?.int)
                        buildJsonObject { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } } }
                    }
                    else -> {
                        assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
                        assertEquals("/v1/chat/completions", request.url.encodedPath)
                        buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", text) } } } }
                    }
                }
                if (provider != "gemini") {
                    assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
                    assertTrue(body["messages"].toString().contains("Das Haus"))
                }
                respond(payload.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
            try {
                val assistant = createLinguisticAssistant(AiSettings(provider, "test-model", "test-key", "https://provider.test/v1"), client)
                assertEquals(provider, assistant.capabilities().provider)
                assertEquals("house", assistant.translateWord("Haus", "Das Haus").translation)
                assertEquals("house", assistant.translateWord("Haus", "Das Haus").translation)
                assertEquals(1, calls, "Successful results should be cached")
            } finally { client.close() }
        }
    }

    @Test fun providerFailureDoesNotLeakResponseAndAllowsRetry(): Unit = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("secret-provider-body", HttpStatusCode.TooManyRequests)
        }) { install(ContentNegotiation) { json() } }
        try {
            val assistant = createLinguisticAssistant(AiSettings("claude", "model", "secret-key", "https://provider.test"), client)
            repeat(2) {
                val failure = assertFailsWith<AiException> { assistant.translateWord("Haus", "Das Haus") }
                assertEquals("AI provider returned HTTP 429", failure.message)
            }
            assertEquals(2, calls, "Failures must not be cached")
        } finally { client.close() }
    }

    @Test fun malformedStructuredOutputAndCancellationAreNotSuccessfulResults(): Unit = runBlocking {
        val malformed = StrategyLinguisticAssistant(TextGenerationStrategy { "not-json" }, "test")
        assertFailsWith<AiException> { malformed.translateWord("Haus", "Das Haus") }
        val cancelled = StrategyLinguisticAssistant(TextGenerationStrategy { throw CancellationException("cancelled") }, "test")
        assertFailsWith<CancellationException> { cancelled.translateWord("Haus", "Das Haus") }
    }
}
