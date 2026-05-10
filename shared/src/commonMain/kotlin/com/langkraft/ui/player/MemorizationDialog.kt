package com.langkraft.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MemorizationDialog(
    text: String,
    onDismiss: () -> Unit
) {
    var hiddenIndices by remember { mutableStateOf(setOf<Int>()) }
    val words = remember(text) { text.split(Regex("\\s+")) }
    
    var level by remember { mutableStateOf(0) } // 0: None, 1: 30%, 2: 60%, 3: All

    LaunchedEffect(level) {
        hiddenIndices = when (level) {
            1 -> words.indices.filter { it % 3 == 0 }.toSet()
            2 -> words.indices.filter { it % 3 != 0 }.toSet()
            3 -> words.indices.toSet()
            else -> emptySet()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prose Memorization") },
        text = {
            Column {
                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Level:", style = MaterialTheme.typography.caption)
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { level = it.toInt() },
                        valueRange = 0f..3f,
                        steps = 2,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                    )
                    Text(text = level.toString(), style = MaterialTheme.typography.body2)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Text Display
                FlowRow(modifier = Modifier.fillMaxWidth()) {
                    words.forEachIndexed { index, word ->
                        val isHidden = hiddenIndices.contains(index)
                        Text(
                            text = if (isHidden) "_____" else word,
                            modifier = Modifier
                                .clickable { 
                                    hiddenIndices = if (isHidden) hiddenIndices - index else hiddenIndices + index
                                }
                                .padding(4.dp),
                            style = MaterialTheme.typography.h6.copy(
                                fontWeight = if (isHidden) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHidden) MaterialTheme.colors.primary else Color.Unspecified
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Tip: Click on words to hide/reveal them manually.",
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("DONE") }
        }
    )
}

@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Simple FlowRow implementation using Box and custom layout or just a simplified version
    // For this context, we'll use a simplified version or just wrap it in a Column if needed.
    // Actually, let's use a Row with wrap if available or just a simple Row for now.
    // In Compose Multiplatform, there is no built-in FlowRow in older versions, 
    // but we can use a basic implementation.
    androidx.compose.foundation.layout.Row(modifier = modifier, horizontalArrangement = Arrangement.Start) {
        content()
    }
}
