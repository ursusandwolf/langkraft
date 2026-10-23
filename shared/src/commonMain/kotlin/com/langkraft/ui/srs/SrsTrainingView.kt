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
import com.langkraft.domain.model.ReviewQuality

import androidx.compose.runtime.LaunchedEffect

@Composable
fun SrsTrainingView(
    viewModel: SrsTrainingViewModel,
    onFinish: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isFinished) {
        if (state.isFinished) {
            onFinish()
        }
    }

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
                    Text(state.error ?: "All caught up!", style = MaterialTheme.typography.h5)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (state.error != null) {
                        Button(onClick = { viewModel.onEvent(SrsEvent.LoadQueue) }) { Text("TRY AGAIN") }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
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
                            onClick = { viewModel.onEvent(SrsEvent.ShowAnswer) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SHOW ANSWER")
                        }
                    } else {
                        if (state.isSaving) Text("Saving review…")
                        state.error?.let { Text(it, color = MaterialTheme.colors.error) }
                        QualityButtons(enabled = !state.isSaving,
                            onQualitySelected = { viewModel.onEvent(SrsEvent.SubmitResult(it)) })
                    }
                }
            }
        }
    }
}

@Composable
fun QualityButtons(enabled: Boolean = true, onQualitySelected: (ReviewQuality) -> Unit) {
    Column {
        Text(
            "How well did you know this?",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.overline
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QualityButton("AGAIN", "Forgot", Color.Red, enabled) { onQualitySelected(ReviewQuality.AGAIN) }
            QualityButton("HARD", "Struggled", Color(0xFFF57C00), enabled) { onQualitySelected(ReviewQuality.HARD) }
            QualityButton("GOOD", "Normal", Color.Green, enabled) { onQualitySelected(ReviewQuality.GOOD) }
            QualityButton("EASY", "Perfect", MaterialTheme.colors.primary, enabled) { onQualitySelected(ReviewQuality.EASY) }
        }
    }
}

@Composable
fun RowScope.QualityButton(label: String, sublabel: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = color, contentColor = Color.White)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.h6)
            Text(sublabel, style = MaterialTheme.typography.caption)
        }
    }
}
