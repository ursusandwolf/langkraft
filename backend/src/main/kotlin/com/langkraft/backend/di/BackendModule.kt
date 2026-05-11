package com.langkraft.backend.di

import com.langkraft.backend.YouTubeIngestionService
import com.langkraft.backend.YtdlpClient
import com.langkraft.backend.ai.GeminiLinguisticAssistant
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.ai.MockLinguisticAssistant
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.koin.dsl.module

val backendModule = module {
    single { YtdlpClient("downloads") }
    single { YouTubeIngestionService(get()) }
    
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
    }

    single<LinguisticAssistant> {
        val apiKey = System.getenv("GEMINI_API_KEY") ?: "mock_key"
        if (apiKey == "mock_key") {
            MockLinguisticAssistant()
        } else {
            GeminiLinguisticAssistant(apiKey, get())
        }
    }
}
