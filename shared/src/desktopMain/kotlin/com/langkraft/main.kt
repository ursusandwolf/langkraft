package com.langkraft

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.langkraft.ui.dashboard.DashboardView
import com.langkraft.ui.dashboard.DashboardViewModel
import com.langkraft.ui.content.ContentSelectionView
import com.langkraft.ui.content.ContentSelectionViewModel
import com.langkraft.ui.player.ImmersionPlayerView
import com.langkraft.ui.player.PlayerViewModel
import com.langkraft.ui.srs.SrsTrainingView
import com.langkraft.ui.srs.SrsTrainingViewModel
import com.langkraft.ui.writing.WritingView
import com.langkraft.ui.writing.WritingViewModel
import com.langkraft.ui.Screen
import com.langkraft.di.initKoin
import com.langkraft.di.createDesktopModule
import com.langkraft.ui.theme.LangkraftTheme
import com.langkraft.ui.BaseViewModel
import org.koin.core.parameter.ParametersDefinition
import com.langkraft.data.profile.*
import com.langkraft.di.ClientRuntime
import com.langkraft.di.commonModule
import com.langkraft.ui.ProfilesPanel
import kotlinx.coroutines.launch
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import androidx.compose.ui.unit.dp

fun main(args: Array<String>) {
    if ("--smoke" in args) {
        initKoin { modules(createDesktopModule(isTui = false)) }
        val koin = org.koin.mp.KoinPlatform.getKoin()
        val handle = koin.get<com.langkraft.data.database.DatabaseHandle>()
        val player = koin.get<com.langkraft.audio.AudioPlayer>()
        player.release()
        handle.close()
        org.koin.core.context.stopKoin()
        println("Desktop wiring smoke OK")
        return
    }

    application {
        val scope = rememberCoroutineScope()
        val store = remember { JvmLocalProfileStore(desktopDataRoot()) }
        var runtime by remember { mutableStateOf<ClientRuntime?>(null) }
        val profiles = remember {
            lateinit var controller: ProfileController
            controller = ProfileController(store, scope) { profile ->
                val previous = runtime
                runtime = null
                previous?.close()
                val app = koinApplication {
                    modules(commonModule, createDesktopModule(dataDirectory = store.directory(profile.id)), module { single { controller } })
                }
                val opened = try { ClientRuntime(app) } catch (failure: Throwable) { app.close(); throw failure }
                try { opened.restore(); runtime = opened }
                catch (failure: Throwable) { opened.close(); throw failure }
            }
            controller
        }
        LaunchedEffect(Unit) { profiles.start() }
        Window(onCloseRequest = { scope.launch { runtime?.close(); exitApplication() } }, title = "Langkraft - German Immersion") {
            LangkraftTheme {
                val active = runtime
                if (active != null) key(active) { MainApp(active.koin) }
                else Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Open a local library")
                    ProfilesPanel(profiles)
                }
            }
        }
    }
}
