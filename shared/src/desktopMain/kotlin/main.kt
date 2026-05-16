import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.remember
import com.langkraft.ui.dashboard.DashboardView
import com.langkraft.ui.dashboard.DashboardViewModel
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
                val dashboardViewModel = koinInject<DashboardViewModel>()
                DashboardView(dashboardViewModel)
            }
        }
    }
}
