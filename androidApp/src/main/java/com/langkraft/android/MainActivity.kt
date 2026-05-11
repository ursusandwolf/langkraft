package com.langkraft.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.langkraft.ui.dashboard.DashboardView
import com.langkraft.ui.dashboard.DashboardViewModel
import com.langkraft.ui.theme.LangkraftTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    
    private val dashboardViewModel: DashboardViewModel by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            LangkraftTheme {
                DashboardView(dashboardViewModel)
            }
        }
    }
}
