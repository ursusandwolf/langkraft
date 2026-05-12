package com.langkraft.di

import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.ai.MockLinguisticAssistant
import com.langkraft.data.repository.SqlDelightContentRepository
import com.langkraft.data.repository.SqlDelightVocabularyRepository
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
import com.langkraft.domain.srs.SpacedRepetitionAlgorithm
import com.langkraft.domain.srs.Sm2Algorithm
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(commonModule)
    }

fun initKoin() = initKoin {}

val commonModule = module {
    // Network
    single { 
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    // Repositories
    single { SqlDelightContentRepository(get(), get(), get()) }
    single<LocalContentRepository> { get<SqlDelightContentRepository>() }
    single<RemoteContentSource> { get<SqlDelightContentRepository>() }
    single<AudioDownloader> { get<SqlDelightContentRepository>() }
    single<VocabularyRepository> { SqlDelightVocabularyRepository(get(), get()) }




    // IO & Hardware
    single<FileSystem> { FileSystemImpl() }
    single<AudioPlayer> { AudioPlayerImpl() }

    // UseCases
    single { IngestContentUseCase(get(), get()) }
    
    // AI
    single<LinguisticAssistant> { MockLinguisticAssistant() }

    // SRS
    single<SpacedRepetitionAlgorithm> { Sm2Algorithm() }

    // ViewModels
    factory { ContentSelectionViewModel(get(), get()) }
    factory { PlayerViewModel(get(), get(), get(), get(), get(), get()) }
    factory { SrsTrainingViewModel(get(), get()) }
    factory { WritingViewModel(get()) }
    factory { DashboardViewModel(get(), get()) }
}
