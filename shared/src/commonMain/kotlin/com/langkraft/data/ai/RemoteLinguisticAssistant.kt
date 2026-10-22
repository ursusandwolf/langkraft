package com.langkraft.data.ai

import com.langkraft.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.*

/** Uses the shared client's current session; provider credentials stay on the backend. */
class RemoteLinguisticAssistant(
    private val client: HttpClient,
    private val baseUrl: Url
) : LinguisticAssistant {
    override suspend fun translateWord(word: String, context: String): TranslationResult =
        request("translate-word", TranslateRequest(word, context))

    override suspend fun translateSentence(text: String): String =
        request<TextRequest, SentenceTranslationResult>("translate-sentence", TextRequest(text)).translation

    override suspend fun analyzeSentence(text: String): DeepAnalysisResult =
        request("analyze-sentence", TextRequest(text))

    override suspend fun correctText(text: String): CorrectionResult =
        request("correct-text", TextRequest(text))

    private suspend inline fun <reified Request : Any, reified Response> request(
        operation: String, payload: Request
    ): Response {
        val response = client.post(baseUrl) {
            url { appendEncodedPathSegments("api", "ai", operation) }
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        check(response.status.isSuccess()) {
            when (response.status) {
                HttpStatusCode.Unauthorized -> "Please sign in through Account to use the AI assistant (HTTP 401)."
                HttpStatusCode.Forbidden -> "Access to the AI assistant was denied (HTTP 403)."
                else -> "AI service returned HTTP ${response.status.value}. Please try again."
            }
        }
        return response.body()
    }
}
