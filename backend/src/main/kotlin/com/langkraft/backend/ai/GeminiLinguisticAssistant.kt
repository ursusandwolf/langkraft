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
            Perform a professional grammatical analysis of this German sentence: "$text".
            Focus on the Ilis Immersion methodology (Deep Analysis).
            Provide the result as a JSON object with fields:
            "words": list of objects with:
                "original": the word as it appears in the text,
                "lemma": the dictionary form (e.g., "gegangen" -> "gehen"),
                "grammaticalInfo": detailed info (e.g., "Noun, feminine, Genitiv, singular" or "Verb, 1st person singular, Präsens, Indikativ"),
                "roleInSentence": (e.g., "Subject", "Indirect Object", "Predicate part"),
            "syntaxExplanation": A clear explanation of the sentence structure, word order (V2, end-placed verb, etc.), and any tricky idiomatic parts.
            
            Output ONLY the JSON object.
        """.trimIndent()

        return callGemini(prompt)
    }

    override suspend fun correctText(text: String): CorrectionResult {
        val prompt = """
            Correct this German text as a supportive language teacher: "$text".
            Focus on the Ilis Immersion methodology (natural phrasing and pedagogical clarity).
            
            Provide the result as a JSON object with fields:
            "originalText": the input text,
            "correctedText": the improved version, making it sound more natural and idiomatic while keeping the user's intended meaning,
            "changes": list of objects with:
                "original": the part being changed,
                "replacement": the correction,
                "explanation": a short, encouraging pedagogical explanation (e.g., explaining why a certain case was used or why the word order changed).
            
            Focus especially on:
            - Word order (V2, end-placed verbs in subordinate clauses).
            - Correct case usage (Nominativ, Akkusativ, Dativ, Genitiv).
            - Natural-sounding vocabulary (idiomatic German).
            
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
