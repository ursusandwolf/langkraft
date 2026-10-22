package com.langkraft.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemorizationDialog(
    text: String,
    onDismiss: () -> Unit
) {
    var hiddenIndices by remember { mutableStateOf(setOf<Int>()) }
    val words = remember(text) { text.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotBlank() } }
    
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
        title = { Text("Prose Memorization", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Internalize the corrected text by hiding words and recalling them.",
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Complexity:", style = MaterialTheme.typography.body2)
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { level = it.toInt() },
                        valueRange = 0f..3f,
                        steps = 2,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                    )
                    Text(
                        text = when(level) {
                            0 -> "Full"
                            1 -> "Easy"
                            2 -> "Hard"
                            else -> "Master"
                        }, 
                        style = MaterialTheme.typography.body2,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Text Display
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        words.forEachIndexed { index, word ->
                            val isHidden = hiddenIndices.contains(index)
                            Text(
                                text = if (isHidden) "_____" else word,
                                modifier = Modifier
                                    .clickable { 
                                        hiddenIndices = if (isHidden) hiddenIndices - index else hiddenIndices + index
                                    }
                                    .padding(horizontal = 2.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.body1.copy(
                                    fontWeight = if (isHidden) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isHidden) MaterialTheme.colors.primary else Color.Unspecified,
                                    fontSize = 18.sp
                                )
                            )
                        }
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
            TextButton(onClick = onDismiss) { 
                Text("DONE", fontWeight = FontWeight.Bold) 
            }
        }
    )
}
