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
            install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 60000 }
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
