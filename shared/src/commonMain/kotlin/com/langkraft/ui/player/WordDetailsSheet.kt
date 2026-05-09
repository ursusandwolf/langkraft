package com.langkraft.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.SubtitleLine
import java.util.UUID

@Composable
fun WordDetailsSheet(
    word: String,
    contextLine: SubtitleLine?,
    translation: String?,
    isSaving: Boolean,
    onSave: (VocabularyWord) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = word, style = MaterialTheme.typography.h4)
            IconButton(onClick = onDismiss) {
                Text("✕") // Simple close button
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Context:",
            style = MaterialTheme.typography.overline
        )
        Text(
            text = contextLine?.textDe ?: "",
            style = MaterialTheme.typography.body1
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Translation:",
            style = MaterialTheme.typography.overline
        )
        if (translation == null) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Text(text = translation, style = MaterialTheme.typography.h6)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                if (translation != null && contextLine != null) {
                    onSave(VocabularyWord(
                        id = UUID.randomUUID().toString(),
                        word = word,
                        lemma = null, // Future: AI provides lemma
                        translation = translation,
                        contextSentence = contextLine.textDe,
                        contentId = contextLine.contentId,
                        subtitleLineId = contextLine.id
                    ))
                }
            },
            enabled = translation != null && !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ADD TO DICTIONARY")
        }
    }
}
