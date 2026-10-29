package com.langkraft.backend.ai

import com.langkraft.backend.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

/** Native Ollama protocol. No credentials, cloud fallback, model download or automatic retry. */
class OllamaTextGenerationStrategy(
    private val settings: AiSettings,
    private val client: HttpClient
) : TextGenerationStrategy {
    private val slots = Semaphore(settings.maxConcurrentRequests)

    override suspend fun generate(request: GenerationRequest): String {
        require(request.prompt.isNotBlank() && request.prompt.length <= 32_000) { "AI prompt is too long or blank" }
        if (!slots.tryAcquire()) throw AiBusyException()
        try {
            return withTimeoutOrNull(settings.timeoutMs) { generateResponse(request) } ?: throw AiTimeoutException()
        } finally { slots.release() }
    }

    private suspend fun generateResponse(request: GenerationRequest): String {
        try {
            return client.preparePost("${settings.baseUrl}/api/generate") {
                if (client.pluginOrNull(HttpTimeout) != null) timeout { requestTimeoutMillis = settings.timeoutMs }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", settings.model)
                    put("prompt", request.prompt)
                    put("stream", false)
                    put("think", false)
                    request.outputSchema?.let { put("format", it) }
                    putJsonObject("options") { put("temperature", 0); put("num_predict", 4096) }
                })
            }.execute { response ->
                when (response.status) {
                    HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable -> throw AiProviderUnavailableException()
                    HttpStatusCode.TooManyRequests -> throw AiBusyException()
                }
                if (!response.status.isSuccess()) throw AiException("Ollama returned HTTP ${response.status.value}")
                if ((response.contentLength() ?: 0) > settings.maxResponseBytes) throw AiException("AI response is too large")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val channel = response.bodyAsChannel()
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count == -1) break
                    if (output.size() + count > settings.maxResponseBytes) throw AiException("AI response is too large")
                    output.write(buffer, 0, count)
                }
                val body = try { Json.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject }
                    catch (_: Exception) { throw AiException("Ollama returned an invalid response") }
                if (body["error"] != null || (body["done"] as? JsonPrimitive)?.booleanOrNull != true ||
                    (body["done_reason"] as? JsonPrimitive)?.contentOrNull == "length") {
                    throw AiException("Ollama returned an incomplete response")
                }
                val result = (body["response"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                result?.takeIf { it.isNotBlank() } ?: throw AiException("Ollama returned an empty response")
            }
        } catch (timeout: HttpRequestTimeoutException) {
            throw AiTimeoutException()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LangkraftException) {
            throw failure
        } catch (_: Exception) {
            throw AiProviderUnavailableException()
        }
    }
}
