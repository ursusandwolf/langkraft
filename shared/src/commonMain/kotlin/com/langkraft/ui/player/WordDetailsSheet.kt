package com.langkraft.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langkraft.domain.ai.TranslationResult
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.io.randomUUID

@Composable
fun WordDetailsSheet(
    word: String,
    contextLine: SubtitleLine?,
    result: TranslationResult?,
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
            Column {
                Text(text = word, style = MaterialTheme.typography.h4)
                if (result != null) {
                    Text(
                        text = "${result.lemma} (${result.partOfSpeech})",
                        style = MaterialTheme.typography.subtitle1,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Text("✕")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "Context:", style = MaterialTheme.typography.overline)
        Text(text = contextLine?.textDe ?: "", style = MaterialTheme.typography.body1)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "Translation:", style = MaterialTheme.typography.overline)
        if (result == null) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Text(text = result.translation, style = MaterialTheme.typography.h6)
            if (result.explanation != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = result.explanation, style = MaterialTheme.typography.body2)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                if (result != null && contextLine != null) {
                    onSave(VocabularyWord(
                        id = randomUUID(),
                        word = word,
                        lemma = result.lemma,
                        translation = result.translation,
                        contextSentence = contextLine.textDe,
                        contentId = contextLine.contentId,
                        subtitleLineId = contextLine.id
                    ))
                }
            },
            enabled = result != null && !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ADD TO DICTIONARY")
        }
    }
}
