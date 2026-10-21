package com.langkraft

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
import com.langkraft.ui.theme.LangkraftTheme
import com.langkraft.ui.BaseViewModel
import org.koin.core.parameter.ParametersDefinition

@Composable
inline fun <reified T : BaseViewModel> rememberViewModel(
    key: Any? = null,
    noinline parameters: ParametersDefinition? = null
): T {
    val koin = org.koin.mp.KoinPlatform.getKoin()
    val viewModel = remember(key) { koin.get<T>(parameters = parameters) }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onCleared()
        }
    }
    return viewModel
}

@Composable
fun MainApp() {
    val koin = org.koin.mp.KoinPlatform.getKoin()
    val session = remember { koin.get<com.langkraft.data.sync.UserSession>() }
    LaunchedEffect(session) { session.restore() }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }

    val scaffoldState = rememberScaffoldState()

    Scaffold(
        scaffoldState = scaffoldState,
        bottomBar = {
            BottomNavigation(backgroundColor = MaterialTheme.colors.surface) {
                BottomNavigationItem(icon={Icon(Icons.Default.Person, null)}, label={Text("Account")},
                    selected=currentScreen is Screen.Account, onClick={currentScreen=Screen.Account})
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
                is Screen.Account -> com.langkraft.ui.AccountView(session, koin.get())
                is Screen.Dashboard -> {
                    DashboardView(rememberViewModel())
                }
                is Screen.ContentSelection -> {
                    ContentSelectionView(rememberViewModel(), onContentSelected = {
                        currentScreen = Screen.Player(it.id)
                    })
                }
                is Screen.SrsTraining -> {
                    SrsTrainingView(rememberViewModel(), onFinish = {
                        currentScreen = Screen.Dashboard
                    })
                }
                is Screen.Writing -> {
                    WritingView(rememberViewModel(), onBack = {
                        currentScreen = Screen.Dashboard
                    })
                }
                is Screen.Player -> {
                    val viewModel = rememberViewModel<PlayerViewModel>(key = screen.contentId)
                    LaunchedEffect(screen.contentId) {
                        viewModel.onEvent(com.langkraft.ui.player.PlayerEvent.LoadContent(screen.contentId))
                    }
                    ImmersionPlayerView(viewModel)
                }
            }
        }
    }
}
