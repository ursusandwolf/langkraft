import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.langkraft.ui.dashboard.DashboardView
import com.langkraft.ui.dashboard.DashboardViewModel
import com.langkraft.di.initKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

import com.langkraft.ui.theme.LangkraftTheme

fun main() = application {
    // Initialize Koin for Desktop
    initKoin()

    Window(onCloseRequest = ::exitApplication, title = "Langkraft - German Immersion") {
        LangkraftTheme {
            // For demonstration, we'll show the Dashboard
            // In a real app, we'd have a NavHost here
            val dashboardViewModel = object : KoinComponent {
                val vm: DashboardViewModel by inject()
            }.vm
            
            DashboardView(dashboardViewModel)
        }
    }
}
