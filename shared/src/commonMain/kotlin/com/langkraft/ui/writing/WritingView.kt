package com.langkraft.ui.writing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.langkraft.domain.ai.TextChange
import com.langkraft.ui.player.MemorizationDialog

@Composable
fun WritingView(
    viewModel: WritingViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    if (state.showMemorization && state.correction != null) {
        MemorizationDialog(
            text = state.correction!!.correctedText,
            onDismiss = { viewModel.toggleMemorization(false) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Writing", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                backgroundColor = MaterialTheme.colors.surface,
                elevation = 0.dp
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                if (state.correction == null) {
                    WritingInputView(
                        text = state.inputText,
                        onTextChanged = { viewModel.onTextChanged(it) },
                        isAnalyzing = state.isAnalyzing,
                        onSubmit = { viewModel.submitForCorrection() }
                    )
                } else {
                    CorrectionResultView(
                        result = state.correction!!,
                        onEditAgain = { viewModel.clearCorrection() },
                        onMemorize = { viewModel.toggleMemorization(true) }
                    )
                }

                state.error?.let {
                    Text(
                        text = "Error: $it",
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            
            if (state.isAnalyzing) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun WritingInputView(
    text: String,
    onTextChanged: (String) -> Unit,
    isAnalyzing: Boolean,
    onSubmit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Schreib etwas auf Deutsch",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Write a diary entry or a summary. AI will analyze your grammar and style.",
            style = MaterialTheme.typography.body2,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            placeholder = { Text("Gestern habe ich...") },
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = MaterialTheme.colors.primary,
                unfocusedBorderColor = Color.LightGray
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isAnalyzing && text.isNotBlank(),
            elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 4.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("ANALYZE MY GERMAN", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CorrectionResultView(
    result: com.langkraft.domain.ai.CorrectionResult,
    onEditAgain: () -> Unit,
    onMemorize: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Verbesserter Text",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold
        )
        
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            color = Color(0xFFF5F5F5),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = result.correctedText,
                    style = MaterialTheme.typography.body1,
                    lineHeight = 24.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = onMemorize,
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("MEMORIZE PROSE", fontSize = 12.sp)
                }
            }
        }
        
        Text(
            "Erklärungen",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
        
        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(result.changes) { change ->
                ChangeCard(change)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = onEditAgain,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("WEITER SCHREIBEN")
        }
    }
}

@Composable
fun ChangeCard(change: TextChange) {
    Card(
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = Color(0xFFFAFAFA),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEEEEE))
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = change.original,
                    style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFFD32F2F),
                    modifier = Modifier
                        .background(Color(0xFFFFEBEE), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Text(
                    " → ",
                    style = MaterialTheme.typography.body1,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Text(
                    text = change.replacement,
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF2E7D32),
                    modifier = Modifier
                        .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            
            if (change.explanation.isNotBlank()) {
                Text(
                    text = change.explanation,
                    style = MaterialTheme.typography.caption,
                    color = Color.DarkGray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
