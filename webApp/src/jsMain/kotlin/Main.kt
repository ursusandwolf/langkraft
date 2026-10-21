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

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    onWasmReady {
        document.getElementById("startup")?.remove()
        CanvasBasedWindow(canvasElementId = "ComposeTarget") {
            LangkraftTheme {
                var handle by remember { mutableStateOf<DatabaseHandle?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                var attempt by remember { mutableStateOf(0) }
                LaunchedEffect(attempt) {
                    error = null
                    try {
                        val opened = createWebDatabase()
                        try {
                            initKoin { modules(createWebModule(opened), org.koin.dsl.module {
                                single { AppConfig(Url(window.location.origin)) }
                            }) }
                            handle = opened
                        } catch (failure: Throwable) { opened.close(); throw failure }
                    } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
                    catch (failure: Throwable) { error = failure.message ?: "Cannot open local storage" }
                }
                if (handle != null) MainApp()
                else Column {
                    if (error == null) Text("Opening local library?")
                    else {
                        Text(error.orEmpty())
                        Button(onClick = { attempt++ }) { Text("Retry") }
                    }
                }
            }
        }
    }
}
