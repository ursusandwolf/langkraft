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
import com.langkraft.di.desktopModule
import org.koin.compose.koinInject
import com.langkraft.ui.theme.LangkraftTheme

fun main() {
    initKoin {
        modules(desktopModule)
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "Langkraft - German Immersion") {
            LangkraftTheme {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    
    val scaffoldState = rememberScaffoldState()
    val scope = rememberCoroutineScope()

    Scaffold(
        scaffoldState = scaffoldState,
        bottomBar = {
            BottomNavigation(backgroundColor = MaterialTheme.colors.surface) {
                BottomNavigationItem(
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Stats") },
                    selected = currentScreen is Screen.Dashboard,
                    onClick = { currentScreen = Screen.Dashboard }
                )
                BottomNavigationItem(
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    label = { Text("Learn") },
                    selected = currentScreen is Screen.ContentSelection || currentScreen is Screen.Player,
                    onClick = { currentScreen = Screen.ContentSelection }
                )
                BottomNavigationItem(
                    icon = { Icon(Icons.Default.CheckCircle, null) },
                    label = { Text("Review") },
                    selected = currentScreen is Screen.SrsTraining,
                    onClick = { currentScreen = Screen.SrsTraining }
                )
                BottomNavigationItem(
                    icon = { Icon(Icons.Default.Edit, null) },
                    label = { Text("Write") },
                    selected = currentScreen is Screen.Writing,
                    onClick = { currentScreen = Screen.Writing }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val screen = currentScreen) {
                is Screen.Dashboard -> {
                    DashboardView(koinInject())
                }
                is Screen.ContentSelection -> {
                    ContentSelectionView(koinInject(), onContentSelected = { 
                        currentScreen = Screen.Player(it.id)
                    })
                }
                is Screen.SrsTraining -> {
                    SrsTrainingView(koinInject(), onFinish = {
                        currentScreen = Screen.Dashboard
                    })
                }
                is Screen.Writing -> {
                    WritingView(koinInject(), onBack = {
                        currentScreen = Screen.Dashboard
                    })
                }
                is Screen.Player -> {
                    val viewModel = koinInject<PlayerViewModel>()
                    // Need to load content in ViewModel
                    LaunchedEffect(screen.contentId) {
                        viewModel.onEvent(com.langkraft.ui.player.PlayerEvent.LoadContent(screen.contentId))
                    }
                    ImmersionPlayerView(viewModel)
                }
            }
        }
    }
}
