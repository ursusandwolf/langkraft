package com.langkraft.backend.ai

import com.langkraft.backend.AiUnavailableException
import com.langkraft.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.server.config.ApplicationConfig

/** Composition root: provider selection happens once, never in the UI or use cases. */
data class AiSettings(val provider: String, val model: String = "", val apiKey: String = "", val baseUrl: String = "") {
    companion object {
        fun load(config: ApplicationConfig, environment: (String) -> String? = System::getenv): AiSettings {
            fun value(name: String, env: String) =
                (config.propertyOrNull("ai.$name")?.getString() ?: environment(env)).orEmpty().trim()
            val provider = value("provider", "AI_PROVIDER").ifBlank { "none" }.lowercase()
            require(provider in setOf("none", "gemini", "claude", "openai")) { "Unsupported AI_PROVIDER" }
            if (provider == "none") return AiSettings(provider)
            val keyName = when (provider) { "gemini" -> "GEMINI_API_KEY"; "claude" -> "ANTHROPIC_API_KEY"; else -> "OPENAI_API_KEY" }
            val key = value("api_key", keyName)
            val model = value("model", "AI_MODEL")
            require(key.isNotBlank()) { "$keyName is required for AI_PROVIDER=$provider" }
            require(model.matches(Regex("[A-Za-z0-9._:/-]+"))) { "AI_MODEL is required and must be a model identifier" }
            val base = value("base_url", "AI_BASE_URL").ifBlank {
                when (provider) { "gemini" -> "https://generativelanguage.googleapis.com/v1beta"; "claude" -> "https://api.anthropic.com/v1"; else -> "https://api.openai.com/v1" }
            }.trimEnd('/')
            require(base.startsWith("https://") || base.startsWith("http://127.0.0.1:") || base.startsWith("http://localhost:")) {
                "AI_BASE_URL must use HTTPS or a local HTTP endpoint"
            }
            return AiSettings(provider, model, key, base)
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
