package com.langkraft.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.langkraft.data.profile.JvmLocalProfileStore
import com.langkraft.data.sync.UserSession
import com.langkraft.di.*
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.data.database.DatabaseHandle
import com.langkraft.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ClientRuntimeTest {
    @Test fun independentContainersRestoreAndCloseWithoutReusingRepositoriesOrSessions() = runTest {
        val root = Files.createTempDirectory("langkraft-client-runtime-").toFile()
        val store = JvmLocalProfileStore(root)
        val firstProfile = store.create("First")
        val secondProfile = store.create("Second")
        fun open(profile: com.langkraft.domain.model.LocalProfile) = ClientRuntime(koinApplication {
            modules(commonModule, createDesktopModule(dataDirectory = store.directory(profile.id)), module {
                single { ClientLifetime(StandardTestDispatcher(testScheduler)) }
            })
        })
        try {
            val first = open(firstProfile)
            val firstSession = first.koin.get<UserSession>()
            val firstRepository = first.koin.get<VocabularyRepository>()
            first.restore()
            firstRepository.saveWord(VocabularyWord("word", "Haus", contextSentence = "Das Haus"))
            val dashboard = first.koin.get<DashboardViewModel>()
            runCurrent()
            first.close()
            dashboard.onCleared()
            val second = open(secondProfile)
            try {
                second.restore()
                assertNotSame(firstSession, second.koin.get<UserSession>())
                assertNotSame(firstRepository, second.koin.get<VocabularyRepository>())
                assertTrue(second.koin.get<DatabaseHandle>().database.appDatabaseQueries.selectAllVocabulary().awaitAsList().isEmpty())
            } finally { second.close() }
            val reopened = open(firstProfile)
            try {
                reopened.restore()
                assertEquals("Haus", reopened.koin.get<DatabaseHandle>().database.appDatabaseQueries.selectAllVocabulary().awaitAsList().single().word)
            } finally { reopened.close() }
        } finally { check(root.deleteRecursively()) }
    }
}
