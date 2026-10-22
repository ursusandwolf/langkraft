package com.langkraft.di

import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.ai.MockLinguisticAssistant
import com.langkraft.data.repository.*
import com.langkraft.data.audio.AudioDownloadCoordinator
import com.langkraft.data.audio.UncoordinatedAudioDownloads
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.RemoteContentSource
import com.langkraft.domain.repository.AudioDownloader
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.usecase.IngestContentUseCase
import com.langkraft.ui.content.ContentSelectionViewModel
import com.langkraft.ui.player.PlayerViewModel
import com.langkraft.ui.srs.SrsTrainingViewModel
import com.langkraft.ui.writing.WritingViewModel
import com.langkraft.ui.dashboard.DashboardViewModel
import com.langkraft.io.FileSystem
import com.langkraft.io.FileSystemImpl
import com.langkraft.audio.AudioPlayer
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.data.sync.ISyncManager
import com.langkraft.data.sync.SyncManager
import com.langkraft.domain.srs.SpacedRepetitionAlgorithm
import com.langkraft.domain.srs.Sm2Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import com.langkraft.data.sync.UserSession
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.http.*
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        modules(commonModule)
        appDeclaration()
    }

data class AppConfig(
    val backendUrl: Url = Url("http://localhost:8080")
)

val commonModule = module {
    single { AppConfig() }

    // Network
    single {
        val owner = this
        HttpClient {
            install(HttpTimeout) { requestTimeoutMillis=30_000; connectTimeoutMillis=10_000 }
            defaultRequest { owner.get<UserSession>().token.value?.let { bearerAuth(it) } }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    single { UserSession(get(), get<AppConfig>().backendUrl) { get<HttpClient>() } }

    // Repositories
    single { SqlDelightContentRepository(get()) }
    single<LocalContentRepository> { get<SqlDelightContentRepository>() }
    single<RemoteContentSource> { BackendRemoteSource(get(), get<AppConfig>().backendUrl) }
    single<AudioDownloader> { AudioDownloaderImpl(get(), get(), get(), get<AppConfig>().backendUrl,
        getOrNull<AudioDownloadCoordinator>() ?: UncoordinatedAudioDownloads) }
    single<VocabularyRepository> { SqlDelightVocabularyRepository(get(), get(), get<AppConfig>().backendUrl) }
    // Sync
    single<ISyncManager> { SyncManager(get()) }

    // IO & Hardware
    single<FileSystem> { FileSystemImpl() }
    // Removed: single<AudioPlayer> { AudioPlayerImpl() }


    // UseCases
    single { IngestContentUseCase(get(), get()) }
    
    // AI
    single<LinguisticAssistant> { MockLinguisticAssistant() }

    // SRS
    single<SpacedRepetitionAlgorithm> { Sm2Algorithm() }

    // ViewModels
    factory { ContentSelectionViewModel(get(), get()) }
    factory { PlayerViewModel(get(), get(), get(), get(), get(), get(), get()) }
    factory { SrsTrainingViewModel(get(), get()) }
    factory { WritingViewModel(get()) }
    factory { DashboardViewModel(get(), get(), get()) }
}
