package com.langkraft.backend.di

import com.langkraft.backend.YouTubeIngestionService
import com.langkraft.backend.BackendMediaStorage
import com.langkraft.backend.MediaLimits
import com.langkraft.backend.YtdlpClient
import com.langkraft.domain.ai.LinguisticAssistant
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.koin.dsl.module

import com.langkraft.backend.db.*
import java.io.File

val backendModule = module {
    single { com.langkraft.backend.BackendWork.from(get()) }
    single { 
        val config = get<io.ktor.server.config.ApplicationConfig>()
        val directory = File(config.propertyOrNull("storage.data_dir")?.getString() ?: "data").apply { mkdirs() }
        val file = File(directory, "langkraft.db")
        BackendDatabase.open(file)
    }
    
    single { BackendUserRepository(get()) }
    single<VocabularySyncRepository> { BackendVocabularyRepository(get()) }
    single { com.langkraft.backend.JwtService(get()) }
    single { com.langkraft.backend.AuthService(get(), get(), get()) }
    single { com.langkraft.backend.SyncService(get(), get(), get()) }

    single {
        val config = get<io.ktor.server.config.ApplicationConfig>()
        BackendMediaStorage(config.propertyOrNull("storage.downloads_dir")?.getString() ?: "downloads",
            config.propertyOrNull("storage.media_retention_ms")?.getString()?.toLong() ?: 0,
            limits = MediaLimits.from(config))
    }
    single { YtdlpClient(get<io.ktor.server.config.ApplicationConfig>().propertyOrNull("storage.downloads_dir")?.getString() ?: "downloads", mediaStorage = get()) }
    single { com.langkraft.backend.IngestionJobRepository(get()) }
    single { YouTubeIngestionService(get(), get(), get()) }
    
    single {
        HttpClient(CIO) {
            followRedirects = false
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 5_000
            }
            install(ContentNegotiation) {
                json()
            }
        }
    }

    single<LinguisticAssistant> {
        val config = get<io.ktor.server.config.ApplicationConfig>()
        com.langkraft.backend.ai.createLinguisticAssistant(
            com.langkraft.backend.ai.AiSettings.load(config), get())
    }
}
