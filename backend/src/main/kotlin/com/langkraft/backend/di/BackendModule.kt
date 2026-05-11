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

import com.langkraft.backend.db.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

val backendModule = module {
    single { 
        val dbFile = File("data").apply { mkdirs() }
        Database.connect("jdbc:sqlite:data/langkraft.db", "org.xerial.sqlite.JDBC").also {
            transaction(it) {
                SchemaUtils.create(Users, VocabularySync)
            }
        }
    }
    
    single { BackendUserRepository() }
    single { BackendVocabularyRepository() }

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
