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
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OllamaStrategyTest {
    private fun mockClient(handler: MockRequestHandler) = HttpClient(MockEngine) {
        engine { addHandler(handler) }
        install(ContentNegotiation) { json() }
    }
    private fun settings(timeoutMs: Long = 120_000, bytes: Int = 1_048_576) =
        AiSettings("ollama", "local-model:4b", baseUrl = "http://127.0.0.1:11434", timeoutMs = timeoutMs, maxResponseBytes = bytes)

    @Test fun localProviderNeedsOnlyAnExplicitModelAndNeverReadsACloudKey() {
        val settings = AiSettings.load(MapApplicationConfig("ai.provider" to "ollama", "ai.model" to "qwen3.5:4b")) {
            if (it.endsWith("API_KEY")) error("No cloud key should be read") else null
        }
        assertEquals("", settings.apiKey)
        assertEquals("http://127.0.0.1:11434", settings.baseUrl)
        assertFailsWith<IllegalArgumentException> { AiSettings.load(MapApplicationConfig("ai.provider" to "ollama")) { null } }
        for (base in listOf("http://external.test:11434", "http://localhost:11434@external.test", "http://localhost:11434/?key=secret", "http://localhost:11434/#x")) {
            assertFailsWith<IllegalArgumentException>(base) {
                AiSettings.load(MapApplicationConfig("ai.provider" to "ollama", "ai.model" to "model", "ai.base_url" to base)) { null }
            }
        }
        for ((key, value) in listOf("timeout_ms" to "0", "timeout_ms" to "120001", "max_concurrent_requests" to "17", "max_response_bytes" to "4294967297")) {
            assertFailsWith<IllegalArgumentException> {
                AiSettings.load(MapApplicationConfig("ai.provider" to "ollama", "ai.model" to "model", "ai.$key" to value)) { null }
            }
        }
    }

    @Test fun allLanguageTasksUseNativeProtocolAndTaskSchemasWithoutCredentials(): Unit = runBlocking {
        var count = 0
        val client = mockClient { request ->
            assertEquals("/api/generate", request.url.encodedPath)
            assertEquals("127.0.0.1", request.url.host)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["x-api-key"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("local-model:4b", body["model"]?.jsonPrimitive?.content)
            assertEquals(false, body["stream"]?.jsonPrimitive?.boolean)
            assertEquals(false, body["think"]?.jsonPrimitive?.boolean)
            val schema = body["format"]?.jsonObject
            val answer = when (count++) {
                0 -> {
                    assertTrue(schema!!["properties"]!!.jsonObject.containsKey("lemma"))
                    """{"translation":"house","lemma":"Haus","partOfSpeech":"noun"}"""
                }
                1 -> { assertNull(schema); "The house is big." }
                2 -> {
                    assertTrue(schema!!["properties"]!!.jsonObject.containsKey("words"))
                    """{"words":[{"original":"Haus","lemma":"Haus","grammaticalInfo":"Noun","roleInSentence":"Subject"}],"syntaxExplanation":"Subject and predicate"}"""
                }
                else -> {
                    assertTrue(schema!!["properties"]!!.jsonObject.containsKey("changes"))
                    """{"originalText":"incorrect echo","correctedText":"Ich gehe.","changes":[]}"""
                }
            }
            respond(buildJsonObject { put("response", answer); put("done", true) }.toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val ai = createLinguisticAssistant(settings(), client)
            assertEquals("ollama", ai.capabilities().provider)
            assertEquals("house", ai.translateWord("Haus", "Das Haus").translation)
            assertEquals("The house is big.", ai.translateSentence("Das Haus ist groß."))
            assertEquals("Haus", ai.analyzeSentence("Das Haus").words.single().lemma)
            assertEquals("Ich gehen.", ai.correctText("Ich gehen.").originalText)
            assertEquals(4, count)
            assertFailsWith<IllegalArgumentException> { ai.translateSentence(" ") }
            assertFailsWith<IllegalArgumentException> { ai.correctText("a".repeat(12_001)) }
            assertEquals(4, count)
        } finally { client.close() }
    }

    @Test fun invalidIncompleteEmptyAndOversizedOutputsNeverBecomeSuccesses(): Unit = runBlocking {
        for (payload in listOf("not-json", """{"done":false,"response":"partial"}""", """{"done":true,"response":" "}""",
            """{"done":true,"response":"partial","done_reason":"length"}""", """{"done":true,"error":"private details"}""",
            """{"done":true,"response":42}""")) {
            val client = mockClient { respond(payload) }
            try {
                val failure = assertFailsWith<AiException> { OllamaTextGenerationStrategy(settings(), client).generate(GenerationRequest("test")) }
                assertFalse(failure.message.orEmpty().contains("private details"))
            } finally { client.close() }
        }
        val client = mockClient { respond("x".repeat(100)) }
        try {
            assertFailsWith<AiException> { OllamaTextGenerationStrategy(settings(bytes = 32), client).generate(GenerationRequest("test")) }
        } finally { client.close() }
        val invalidTask = StrategyLinguisticAssistant(TextGenerationStrategy { """{"translation":"","lemma":"Haus","partOfSpeech":"noun"}""" }, "test")
        assertFailsWith<AiException> { invalidTask.translateWord("Haus", "Das Haus") }
    }

    @Test fun unavailableAndOverloadedResponsesAreSafeAndDoNotFallback(): Unit = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            var calls = 0
            val client = mockClient { calls++; respond("private body", status) }
            try {
                val failure = assertFailsWith<LangkraftException> { OllamaTextGenerationStrategy(settings(), client).generate(GenerationRequest("test")) }
                when (status) {
                    HttpStatusCode.TooManyRequests -> assertIs<AiBusyException>(failure)
                    HttpStatusCode.InternalServerError -> assertIs<AiException>(failure)
                    else -> assertIs<AiProviderUnavailableException>(failure)
                }
                assertFalse(failure.message.orEmpty().contains("private body"))
                assertEquals(1, calls)
            } finally { client.close() }
        }
    }

    @Test fun concurrencyIsBoundedAndCancellationReleasesTheSlot(): Unit = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val client = mockClient {
            calls++
            if (calls == 1) { started.complete(Unit); awaitCancellation() }
            respond("""{"done":true,"response":"ok"}""")
        }
        try {
            val strategy = OllamaTextGenerationStrategy(settings(), client)
            val first = async { strategy.generate(GenerationRequest("first")) }
            started.await()
            assertFailsWith<AiBusyException> { strategy.generate(GenerationRequest("second")) }
            first.cancelAndJoin()
            assertEquals("ok", strategy.generate(GenerationRequest("retry")))
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun timeoutIsTypedAndExternalCancellationStillPropagates(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val client = mockClient { started.complete(Unit); awaitCancellation() }
        try {
            val strategy = OllamaTextGenerationStrategy(settings(timeoutMs = 25), client)
            assertFailsWith<AiTimeoutException> { strategy.generate(GenerationRequest("timeout")) }
            val cancelled = async { strategy.generate(GenerationRequest("cancel")) }

            cancelled.cancel()
            assertFailsWith<CancellationException> { cancelled.await() }
        } finally { client.close() }
    }
}
