package com.langkraft.ui.writing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.langkraft.domain.ai.TextChange

@Composable
fun WritingView(
    viewModel: WritingViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Writing") },
                navigationIcon = {
                    Button(onClick = onBack) { Text("<") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            if (state.correction == null) {
                Text(
                    "Write a summary or a diary entry in German. AI will correct it and explain your mistakes.",
                    style = MaterialTheme.typography.body2,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = { viewModel.onTextChanged(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    placeholder = { Text("Gestern habe ich ein Video gesehen...") },
                    label = { Text("Your German Text") }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.submitForCorrection() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isAnalyzing && state.inputText.isNotBlank()
                ) {
                    if (state.isAnalyzing) CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    else Text("CHECK MY GERMAN")
                }
            } else {
                CorrectionResultView(
                    result = state.correction!!,
                    onEditAgain = { viewModel.clearCorrection() }
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun CorrectionResultView(
    result: com.langkraft.domain.ai.CorrectionResult,
    onEditAgain: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Corrected Text", style = MaterialTheme.typography.h6)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            color = MaterialTheme.colors.primary.copy(alpha = 0.05f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = result.correctedText,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.body1
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Changes & Explanations", style = MaterialTheme.typography.h6)
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(result.changes) { change ->
                ChangeItem(change)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onEditAgain,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("EDIT & TRY AGAIN")
        }
    }
}

@Composable
fun ChangeItem(change: TextChange) {
    Column(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = change.original,
                style = MaterialTheme.typography.body2,
                color = Color.Red,
                modifier = Modifier.background(Color.Red.copy(alpha = 0.1f)).padding(2.dp)
            )
            Text(" → ", style = MaterialTheme.typography.body1)
            Text(
                text = change.replacement,
                style = MaterialTheme.typography.body1,
                color = Color(0xFF388E3C), // Dark Green
                modifier = Modifier.background(Color.Green.copy(alpha = 0.1f)).padding(2.dp)
            )
        }
        Text(
            text = change.explanation,
            style = MaterialTheme.typography.caption,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp)
        )
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}
