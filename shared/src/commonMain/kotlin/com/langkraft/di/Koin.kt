package com.langkraft.di

import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.ai.MockLinguisticAssistant
import com.langkraft.data.repository.SqlDelightContentRepository
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.domain.repository.ContentRepository
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.domain.usecase.IngestContentUseCase
import com.langkraft.ui.content.ContentSelectionViewModel
import com.langkraft.ui.player.PlayerViewModel
import com.langkraft.ui.srs.SrsTrainingViewModel
import io.ktor.client.HttpClient
import com.langkraft.ui.writing.WritingViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(commonModule)
    }

// For iOS/other targets where startKoin is called without declaration
fun initKoin() = initKoin {}

val commonModule = module {
    // Repositories
    single<ContentRepository> { SqlDelightContentRepository(get(), get()) }
    single<VocabularyRepository> { SqlDelightVocabularyRepository(get()) }

    // UseCases
    single { IngestContentUseCase(get()) }
    
    // AI
    single<LinguisticAssistant> { MockLinguisticAssistant() }

    // ViewModels
    factory { ContentSelectionViewModel(get(), get()) }
    factory { PlayerViewModel(get(), get(), get()) }
    factory { SrsTrainingViewModel(get()) }
    factory { WritingViewModel(get()) }
}
