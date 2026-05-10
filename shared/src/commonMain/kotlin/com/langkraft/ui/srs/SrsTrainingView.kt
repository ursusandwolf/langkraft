package com.langkraft.ui.srs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.langkraft.domain.model.VocabularyWord

@Composable
fun SrsTrainingView(
    viewModel: SrsTrainingViewModel,
    onFinish: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily Review") },
                actions = {
                    Text(
                        text = "${state.remainingCount} left",
                        modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.body2
                    )
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.currentWord == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("All caught up!", style = MaterialTheme.typography.h5)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onFinish) { Text("BACK TO LIBRARY") }
                }
            } else {
                val word = state.currentWord!!
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Context Sentence (Target)
                    Text(
                        text = word.contextSentence,
                        style = MaterialTheme.typography.h5,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))

                    AnimatedVisibility(
                        visible = state.isAnswerVisible,
                        enter = fadeIn() + expandVertically()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = word.word,
                                style = MaterialTheme.typography.h4,
                                color = MaterialTheme.colors.primary
                            )
                            Text(
                                text = word.translation ?: "",
                                style = MaterialTheme.typography.subtitle1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    if (!state.isAnswerVisible) {
                        Button(
                            onClick = { viewModel.showAnswer() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SHOW ANSWER")
                        }
                    } else {
                        QualityButtons(onQualitySelected = { viewModel.submitResult(it) })
                    }
                }
            }
        }
    }
}

@Composable
fun QualityButtons(onQualitySelected: (Int) -> Unit) {
    Column {
        Text(
            "How well did you know this?",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.overline
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QualityButton("0", "Forgot", Color.Red) { onQualitySelected(0) }
            QualityButton("3", "Hard", Color.Orange) { onQualitySelected(3) }
            QualityButton("4", "Good", Color.Green) { onQualitySelected(4) }
            QualityButton("5", "Easy", MaterialTheme.colors.primary) { onQualitySelected(5) }
        }
    }
}

@Composable
fun RowScope.QualityButton(label: String, sublabel: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = color, contentColor = Color.White)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.h6)
            Text(sublabel, style = MaterialTheme.typography.caption)
        }
    }
}
