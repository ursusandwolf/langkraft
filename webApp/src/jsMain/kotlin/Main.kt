package com.langkraft.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import com.langkraft.ui.dashboard.DashboardView
import com.langkraft.ui.dashboard.DashboardViewModel
import com.langkraft.ui.theme.LangkraftTheme
import com.langkraft.di.initKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.jetbrains.skiko.wasm.onWasmReady

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    onWasmReady {
        initKoin()
        CanvasBasedWindow("Langkraft") {
            LangkraftTheme {
                val vm = object : KoinComponent {
                    val viewModel: DashboardViewModel by inject()
                }.viewModel
                DashboardView(vm)
            }
        }
    }
}
