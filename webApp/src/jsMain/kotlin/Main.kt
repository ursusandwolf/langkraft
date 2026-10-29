package com.langkraft.web

import androidx.compose.runtime.*
import androidx.compose.material.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.CanvasBasedWindow
import com.langkraft.MainApp
import com.langkraft.data.database.DatabaseHandle
import com.langkraft.data.database.createWebDatabase
import com.langkraft.di.initKoin
import com.langkraft.di.createWebModule
import com.langkraft.di.AppConfig
import com.langkraft.ui.theme.LangkraftTheme
import io.ktor.http.Url
import kotlinx.browser.window
import kotlinx.browser.document
import org.jetbrains.skiko.wasm.onWasmReady
import com.langkraft.data.profile.ProfileController
import com.langkraft.data.profile.WebLocalProfileStore
import com.langkraft.data.storage.selectedProfile
import com.langkraft.data.storage.localProfileUrl
import com.langkraft.di.ClientRuntime
import com.langkraft.di.commonModule
import com.langkraft.ui.ProfilesPanel
import kotlinx.coroutines.await
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    onWasmReady {
        document.getElementById("startup")?.remove()
        CanvasBasedWindow(canvasElementId = "ComposeTarget") {
            LangkraftTheme {
                val scope = rememberCoroutineScope()
                var runtime by remember { mutableStateOf<ClientRuntime?>(null) }
                val profiles = remember {
                    lateinit var controller: ProfileController
                    controller = ProfileController(WebLocalProfileStore(), scope) { profile ->
                        if (runtime != null || profile.id.value != selectedProfile) {
                            val target = localProfileUrl(profile.id.value).await()
                            runtime?.close()
                            runtime = null
                            window.location.assign(target)
                            return@ProfileController
                        }
                        val opened = createWebDatabase()
                        var active: ClientRuntime? = null
                        try {
                            val app = koinApplication { modules(commonModule, createWebModule(opened), module {
                                single { AppConfig(Url(window.location.origin)) }; single { controller }
                            }) }
                            active = ClientRuntime(app)
                            active.restore()
                            runtime = active
                        } catch (failure: Throwable) { if (active != null) active.close() else opened.close(); throw failure }
                    }
                    controller
                }
                LaunchedEffect(Unit) { profiles.start() }
                val active = runtime
                if (active != null) {
                    key(active) { MainApp(active.koin) }
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        withFrameNanos { }
                        document.documentElement?.setAttribute("data-app-ready", "true")
                    }
                }
                else Column {
                    Text("Opening local library")
                    ProfilesPanel(profiles)
                }
            }
        }
    }
}
