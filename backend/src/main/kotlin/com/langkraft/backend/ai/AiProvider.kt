package com.langkraft.backend.ai

import com.langkraft.backend.AiUnavailableException
import com.langkraft.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.server.config.ApplicationConfig
import java.net.URI

/** Composition root: provider selection happens once, never in the UI or use cases. */
data class AiSettings(
    val provider: String,
    val model: String = "",
    val apiKey: String = "",
    val baseUrl: String = "",
    val timeoutMs: Long = 120_000,
    val maxConcurrentRequests: Int = 1,
    val maxResponseBytes: Int = 1_048_576
) {
    init {
        require(timeoutMs in 1..120_000) { "AI timeout must be between 1 and 120000 ms" }
        require(maxConcurrentRequests in 1..16) { "AI concurrency must be between 1 and 16" }
        require(maxResponseBytes in 1..4_194_304) { "AI response limit must be between 1 and 4194304 bytes" }
    }
    companion object {
        fun load(config: ApplicationConfig, environment: (String) -> String? = System::getenv): AiSettings {
            fun value(name: String, env: String) =
                (config.propertyOrNull("ai.$name")?.getString() ?: environment(env)).orEmpty().trim()
            val provider = value("provider", "AI_PROVIDER").ifBlank { "none" }.lowercase()
            require(provider in setOf("none", "ollama", "gemini", "claude", "openai")) { "Unsupported AI_PROVIDER" }
            if (provider == "none") return AiSettings(provider)
            val keyName = when (provider) { "gemini" -> "GEMINI_API_KEY"; "claude" -> "ANTHROPIC_API_KEY"; else -> "OPENAI_API_KEY" }
            val key = if (provider == "ollama") "" else value("api_key", keyName)
            val model = value("model", "AI_MODEL")
            require(provider == "ollama" || key.isNotBlank()) { "$keyName is required for AI_PROVIDER=$provider" }
            require(model.matches(Regex("[A-Za-z0-9._:/-]+"))) { "AI_MODEL is required and must be a model identifier" }
            val base = value("base_url", "AI_BASE_URL").ifBlank {
                when (provider) { "ollama" -> "http://127.0.0.1:11434"; "gemini" -> "https://generativelanguage.googleapis.com/v1beta"; "claude" -> "https://api.anthropic.com/v1"; else -> "https://api.openai.com/v1" }
            }.trimEnd('/')
            val uri = try { URI(base) } catch (_: Exception) { throw IllegalArgumentException("Invalid AI_BASE_URL") }
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.port in -1..65535 && (uri.scheme == "https" ||
                    (uri.scheme == "http" && uri.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]")))) {
                "AI_BASE_URL must use HTTPS or loopback HTTP without credentials, query or fragment"
            }
            fun number(name: String, env: String, default: Long): Long {
                val raw = value(name, env)
                return if (raw.isBlank()) default else raw.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid $env")
            }
            val concurrency = number("max_concurrent_requests", "AI_MAX_CONCURRENT_REQUESTS", 1)
            val responseBytes = number("max_response_bytes", "AI_MAX_RESPONSE_BYTES", 1_048_576)
            require(concurrency in 1..16 && responseBytes in 1..4_194_304) { "Invalid AI limits" }
            return AiSettings(provider, model, key, base,
                number("timeout_ms", "AI_TIMEOUT_MS", 120_000), concurrency.toInt(), responseBytes.toInt())
        }
    }
}

fun createLinguisticAssistant(settings: AiSettings, client: HttpClient): LinguisticAssistant =
    if (settings.provider == "none") DisabledLinguisticAssistant()
    else {
        val strategy = when (settings.provider) {
            "gemini" -> GeminiTextGenerationStrategy(settings, client)
            "claude" -> ClaudeTextGenerationStrategy(settings, client)
            "openai" -> OpenAiTextGenerationStrategy(settings, client)
            "ollama" -> OllamaTextGenerationStrategy(settings, client)
            else -> error("Unsupported AI provider")
        }
        CachingLinguisticAssistant(StrategyLinguisticAssistant(strategy, settings.provider))
    }

class DisabledLinguisticAssistant : LinguisticAssistant {
    override suspend fun capabilities() = AiCapabilities(false)
    override suspend fun translateWord(word: String, context: String): TranslationResult = throw AiUnavailableException()
    override suspend fun translateSentence(text: String): String = throw AiUnavailableException()
    override suspend fun analyzeSentence(text: String): DeepAnalysisResult = throw AiUnavailableException()
    override suspend fun correctText(text: String): CorrectionResult = throw AiUnavailableException()
}
