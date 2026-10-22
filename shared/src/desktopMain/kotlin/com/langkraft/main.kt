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

fun main(args: Array<String>) {
    initKoin {
        modules(createDesktopModule(isTui = false))
    }

    if ("--smoke" in args) {
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
        Window(onCloseRequest = ::exitApplication, title = "Langkraft - German Immersion") {
            LangkraftTheme {
                MainApp()
            }
        }
    }
}
