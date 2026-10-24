package com.langkraft.data

import com.langkraft.data.ai.RemoteLinguisticAssistant
import com.langkraft.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class RemoteLinguisticAssistantTest {
    @Test fun slowCapabilityProbeFallsBackToManualMode() = runTest {
        val client = HttpClient(MockEngine { awaitCancellation() }) {
            install(ContentNegotiation) { json() }
        }
        try {
            assertFalse(RemoteLinguisticAssistant(client, Url("https://example.test")).capabilities().enabled)
        } finally { client.close() }
    }
    @Test fun sendsAllOperationsWithCurrentSessionAndPreservesBasePath() = runTest {
        var token: String? = "first"
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(token?.let { "Bearer $it" }, request.headers[HttpHeaders.Authorization])
            assertEquals("example.test", request.url.host)
            val body = (request.body as TextContent).text
            val path = request.url.encodedPath
            paths += path
            val response = when (path) {
                "/proxy/api/ai/translate-word" -> {
                    assertEquals(TranslateRequest("Häuser", "Die Häuser sind groß."), Json.decodeFromString<TranslateRequest>(body))
                    Json.encodeToString(TranslationResult.serializer(), TranslationResult("houses", "Haus", "noun"))
                }
                "/proxy/api/ai/translate-sentence" -> {
                    assertEquals(TextRequest("Grüß dich!"), Json.decodeFromString<TextRequest>(body))
                    """{"translation":"Hello!"}"""
                }
                "/proxy/api/ai/analyze-sentence" -> {
                    assertEquals(TextRequest("Das Haus"), Json.decodeFromString<TextRequest>(body))
                    """{"words":[{"original":"Haus","lemma":"Haus","grammaticalInfo":"Noun","roleInSentence":"Subject"}],"syntaxExplanation":"A subject"}"""
                }
                "/proxy/api/ai/correct-text" -> {
                    assertEquals(TextRequest("Ich gehen"), Json.decodeFromString<TextRequest>(body))
                    """{"originalText":"Ich gehen","correctedText":"Ich gehe","changes":[{"original":"gehen","replacement":"gehe","explanation":"First person"}]}"""
                }
                else -> error("Unexpected request: $path")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json() }
            defaultRequest { token?.let { bearerAuth(it) } }
        }
        try {
            val assistant = RemoteLinguisticAssistant(client, Url("https://example.test/proxy/"))
            assertEquals(TranslationResult("houses", "Haus", "noun"), assistant.translateWord("Häuser", "Die Häuser sind groß."))
            token = "second"
            assertEquals("Hello!", assistant.translateSentence("Grüß dich!"))
            assertEquals("Haus", assistant.analyzeSentence("Das Haus").words.single().lemma)
            token = null
            assertEquals("gehe", assistant.correctText("Ich gehen").changes.single().replacement)
            assertEquals(4, paths.size)
        } finally { client.close() }
    }

    @Test fun rejectsHttpErrorsBeforeDecodingWithoutLeakingResponseBody() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            val client = HttpClient(MockEngine { respond("private-provider-error", status) }) {
                install(ContentNegotiation) { json() }
            }
            try {
                val assistant = RemoteLinguisticAssistant(client, Url("https://example.test"))
                val failure = assertFailsWith<IllegalStateException> { assistant.translateSentence("Hallo") }
                assertContains(failure.message.orEmpty(), status.value.toString())
                assertFalse(failure.message.orEmpty().contains("private-provider-error"))
                if (status == HttpStatusCode.Unauthorized) assertContains(failure.message.orEmpty(), "Account")
            } finally { client.close() }
        }
    }

    @Test fun propagatesCancellationAndNetworkFailures() = runTest {
        val failures = listOf(CancellationException("cancelled"), java.io.IOException("offline"))
        for (failure in failures) {
            val client = HttpClient(MockEngine { throw failure }) { install(ContentNegotiation) { json() } }
            try {
                val assistant = RemoteLinguisticAssistant(client, Url("https://example.test"))
                if (failure is CancellationException) {
                    assertFailsWith<CancellationException> { assistant.translateSentence("Hallo") }
                } else {
                    assertFailsWith<java.io.IOException> { assistant.translateSentence("Hallo") }
                }
            } finally { client.close() }
        }
    }

    @Test fun rejectsMalformedSuccessInsteadOfReturningMockData() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"unexpected":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            assertFails { RemoteLinguisticAssistant(client, Url("https://example.test")).analyzeSentence("Hallo") }
        } finally { client.close() }
    }
}
