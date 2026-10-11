package com.langkraft.web

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.data.*
import com.langkraft.ui.*
import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        var services by remember { mutableStateOf<AppServices?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            try {
                val local = createBrowserDatabase()
                services = AppServices(local, createHttpClient(), BrowserAudioStore(), AudioPlayerImpl(), "http://localhost:8080")
            } catch (failure: Exception) { error = failure.message ?: "Cannot open local database" }
        }
        DisposableEffect(services) {
            val listener: (org.w3c.dom.events.Event) -> Unit = { services?.close() }
            window.addEventListener("pagehide", listener)
            onDispose { window.removeEventListener("pagehide", listener); services?.close() }
        }
        services?.let { LangkraftApp(it) } ?: MaterialTheme {
            if (error == null) CircularProgressIndicator() else Text(error!!)
        }
    }
}