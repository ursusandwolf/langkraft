package com.langkraft.backend.ai

import com.langkraft.backend.AiException
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.ai.TranslationResult
import com.langkraft.domain.ai.DeepAnalysisResult
import com.langkraft.domain.ai.CorrectionResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GeminiLinguisticAssistant(
    private val apiKey: String,
    private val httpClient: HttpClient
) : LinguisticAssistant {

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    private val jsonIgnoreUnknown = Json { ignoreUnknownKeys = true }

    override suspend fun translateWord(word: String, context: String): TranslationResult {
        val prompt = """
            Translate the German word "$word" in the context of this sentence: "$context".
            Provide the result as a JSON object with fields:
            "translation" (string), "lemma" (base form, string), "partOfSpeech" (string), "explanation" (string, optional).
            Output ONLY the JSON object.
        """.trimIndent()

        return callGemini(prompt)
    }

    override suspend fun translateSentence(text: String): String {
        val prompt = "Translate this German sentence to English: \"$text\". Output only the translation."
        val response: GeminiResponse = callGeminiRaw(prompt)
        return response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim() ?: "Translation failed"
    }

    override suspend fun analyzeSentence(text: String): DeepAnalysisResult {
        val prompt = """
            Perform a deep grammatical analysis of this German sentence: "$text".
            Provide the result as a JSON object with fields:
            "words" (list of objects with: "original", "lemma", "grammaticalInfo", "roleInSentence"),
            "syntaxExplanation" (string).
            Output ONLY the JSON object.
        """.trimIndent()

        return callGemini(prompt)
    }

    override suspend fun correctText(text: String): CorrectionResult {
        val prompt = """
            Correct this German text and explain the changes: "$text".
            Provide the result as a JSON object with fields:
            "originalText" (string), "correctedText" (string), "changes" (list of objects with: "original", "replacement", "explanation").
            Output ONLY the JSON object.
        """.trimIndent()

        return callGemini(prompt)
    }

    private suspend inline fun <reified T> callGemini(prompt: String): T {
        val rawResponse: GeminiResponse = callGeminiRaw(prompt)
        val text = rawResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?.replace("```json", "")
            ?.replace("```", "")
            ?.trim() ?: throw AiException("Gemini returned empty response")
        
        return try {
            jsonIgnoreUnknown.decodeFromString(text)
        } catch (e: Exception) {
            throw AiException("Failed to parse Gemini response: $text", e)
        }
    }

    private suspend fun callGeminiRaw(prompt: String): GeminiResponse {
        val response = try {
            httpClient.post("$baseUrl?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(GeminiRequest(listOf(GeminiContent(listOf(GeminiPart(prompt))))))
            }
        } catch (e: Exception) {
            throw AiException("Failed to call Gemini API", e)
        }

        if (!response.status.isSuccess()) {
            throw AiException("Gemini API error: ${response.bodyAsText()}")
        }

        return response.body()
    }
}

@Serializable
data class GeminiRequest(val contents: List<GeminiContent>)

@Serializable
data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
data class GeminiPart(val text: String)

@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate>)

@Serializable
data class GeminiCandidate(val content: GeminiContent)
