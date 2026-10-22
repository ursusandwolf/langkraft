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
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

val backendModule = module {
    single { 
        val config = get<io.ktor.server.config.ApplicationConfig>()
        val directory = File(config.propertyOrNull("storage.data_dir")?.getString() ?: "data").apply { mkdirs() }
        val file = File(directory, "langkraft.db")
        Class.forName("org.sqlite.JDBC")
        if (file.isFile && !File(file.path + ".before-sync-v2.bak").exists()) {
            java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { it.execute("VACUUM INTO '${(file.path + ".before-sync-v2.bak").replace("'", "''")}'") }
            }
        }
        Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC").also {
            transaction(it) {
                SchemaUtils.createMissingTablesAndColumns(Users, VocabularySync, IngestionJobs)
                VocabularySync.update({ VocabularySync.serverUpdated eq 0L }) { row -> row[serverUpdated] = System.currentTimeMillis() }
            }
        }
    }
    
    single { BackendUserRepository(get()) }
    single<VocabularySyncRepository> { BackendVocabularyRepository(get()) }
    single { com.langkraft.backend.JwtService(get()) }
    single { com.langkraft.backend.AuthService(get(), get()) }
    single { com.langkraft.backend.SyncService(get(), get()) }

    single { YtdlpClient(get<io.ktor.server.config.ApplicationConfig>().propertyOrNull("storage.downloads_dir")?.getString() ?: "downloads") }
    single { com.langkraft.backend.IngestionJobRepository(get()) }
    single { YouTubeIngestionService(get(), get(), get()) }
    
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
    }

    single<LinguisticAssistant> {
        val config = get<io.ktor.server.config.ApplicationConfig>()
        val apiKey = config.propertyOrNull("gemini.api_key")?.getString() 
            ?: System.getenv("GEMINI_API_KEY") 
            ?: "mock_key"
            
        val baseAssistant = if (apiKey == "mock_key") {
            MockLinguisticAssistant()
        } else {
            GeminiLinguisticAssistant(apiKey, get())
        }
        com.langkraft.backend.ai.CachingLinguisticAssistant(baseAssistant)
    }
}
