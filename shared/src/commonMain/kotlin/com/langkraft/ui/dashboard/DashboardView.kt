package com.langkraft.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DashboardView(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Progress & Stats", fontWeight = FontWeight.Bold) },
                backgroundColor = MaterialTheme.colors.surface,
                elevation = 0.dp
            ) 
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            ImmersionHeader(state.totalImmersionSeconds, state.totalContent)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Vocabulary Mastery", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            VocabularyOverview(state)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("SRS Status", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            SrsCard(state.wordsToReviewToday)
        }
    }
}

@Composable
fun ImmersionHeader(seconds: Long, count: Long) {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
        backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Total Immersion Time", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
                Text(
                    text = "${hours}h ${minutes}m",
                    style = MaterialTheme.typography.h3,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colors.primary
                )
                Text("from $count videos", style = MaterialTheme.typography.caption)
            }
            Icon(
                Icons.Default.Info, 
                contentDescription = null, 
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
fun VocabularyOverview(state: DashboardState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatBox(
            modifier = Modifier.weight(1f),
            label = "Mastered",
            value = state.wordsMastered.toString(),
            color = Color(0xFF2E7D32)
        )
        StatBox(
            modifier = Modifier.weight(1f),
            label = "Learning",
            value = state.wordsLearning.toString(),
            color = Color(0xFFF57C00)
        )
        StatBox(
            modifier = Modifier.weight(1f),
            label = "New",
            value = state.wordsNew.toString(),
            color = MaterialTheme.colors.primary
        )
    }
}

@Composable
fun StatBox(modifier: Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        elevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.caption, color = Color.Gray)
        }
    }
}

@Composable
fun SrsCard(dueCount: Int) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
        backgroundColor = if (dueCount > 0) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (dueCount > 0) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                        RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (dueCount > 0) Icons.Default.List else Icons.Default.Star,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = if (dueCount > 0) "$dueCount words due for review" else "All caught up!",
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (dueCount > 0) "Stay consistent to master them." else "Excellent work today.",
                    style = MaterialTheme.typography.caption
                )
            }
        }
    }
}
