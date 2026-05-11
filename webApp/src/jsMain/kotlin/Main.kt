package com.langkraft.web

import androidx.compose.ui.window.Window
import com.langkraft.ui.dashboard.DashboardView
import com.langkraft.ui.dashboard.DashboardViewModel
import com.langkraft.ui.theme.LangkraftTheme
import com.langkraft.di.initKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.jetbrains.skiko.wasm.onWasmReady

fun main() {
    onWasmReady {
        initKoin()
        Window("Langkraft") {
            LangkraftTheme {
                val vm = object : KoinComponent {
                    val viewModel: DashboardViewModel by inject()
                }.viewModel
                DashboardView(vm)
            }
        }
    }
}
