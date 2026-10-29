package com.langkraft.backend.ai

import com.langkraft.backend.AiException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Strategy: each implementation owns its provider's wire protocol. */
data class GenerationRequest(val prompt: String, val outputSchema: JsonObject? = null)

fun interface TextGenerationStrategy {
    suspend fun generate(request: GenerationRequest): String
}

abstract class HttpTextGenerationStrategy(protected val settings: AiSettings, private val client: HttpClient) : TextGenerationStrategy {
    protected suspend fun request(path: String, payload: JsonObject, headers: HttpRequestBuilder.() -> Unit): JsonObject {
        try {
            val response = client.post("${settings.baseUrl}/$path") {
                contentType(ContentType.Application.Json)
                headers()
                setBody(payload)
            }
            if (!response.status.isSuccess()) throw AiException("AI provider returned HTTP ${response.status.value}")
            return response.body()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AiException) {
            throw failure
        } catch (failure: Exception) {
            // Never expose provider response bodies or credentials to the client/logs.
            throw AiException("AI provider request failed")
        }
    }

    protected fun nonEmpty(text: String?): String = text?.trim()?.takeIf { it.isNotBlank() }
        ?: throw AiException("AI provider returned an empty response")
}

class GeminiTextGenerationStrategy(settings: AiSettings, client: HttpClient) : HttpTextGenerationStrategy(settings, client) {
    override suspend fun generate(request: GenerationRequest): String {
        val prompt = request.prompt
        val response = request("models/${settings.model}:generateContent", buildJsonObject {
            putJsonArray("contents") { addJsonObject { putJsonArray("parts") { addJsonObject { put("text", prompt) } } } }
        }) { header("x-goog-api-key", settings.apiKey) }
        val candidate = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
        return nonEmpty(parts?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }?.joinToString("\n"))
    }
}

class ClaudeTextGenerationStrategy(settings: AiSettings, client: HttpClient) : HttpTextGenerationStrategy(settings, client) {
    override suspend fun generate(request: GenerationRequest): String {
        val prompt = request.prompt
        val response = request("messages", buildJsonObject {
            put("model", settings.model)
            put("max_tokens", 4096)
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", prompt) } }
        }) { header("x-api-key", settings.apiKey); header("anthropic-version", "2023-06-01") }
        return nonEmpty(response["content"]?.jsonArray?.mapNotNull {
            val block = it.jsonObject
            if (block["type"]?.jsonPrimitive?.contentOrNull == "text") block["text"]?.jsonPrimitive?.contentOrNull else null
        }?.joinToString("\n"))
    }
}

class OpenAiTextGenerationStrategy(settings: AiSettings, client: HttpClient) : HttpTextGenerationStrategy(settings, client) {
    override suspend fun generate(request: GenerationRequest): String {
        val prompt = request.prompt
        val response = request("chat/completions", buildJsonObject {
            put("model", settings.model)
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", prompt) } }
        }) { bearerAuth(settings.apiKey) }
        val message = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        return nonEmpty(message?.get("content")?.jsonPrimitive?.contentOrNull)
    }
}
