package com.langkraft.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DashboardView(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Learning Progress") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            DashboardCard("Listening Hours", "${state.listeningHours} hrs")
            Spacer(modifier = Modifier.height(16.dp))
            DashboardCard("Sentences Mastered", "${state.sentencesMastered}")
            Spacer(modifier = Modifier.height(16.dp))
            Text("Weekly Goal", style = MaterialTheme.typography.h6)
            LinearProgressIndicator(
                progress = state.weeklyGoalProgress,
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
        }
    }
}

@Composable
fun DashboardCard(title: String, value: String) {
    Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.subtitle2)
            Text(value, style = MaterialTheme.typography.h4)
        }
    }
}
