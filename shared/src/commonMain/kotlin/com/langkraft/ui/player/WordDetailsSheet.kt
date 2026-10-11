package com.langkraft.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langkraft.domain.model.SubtitleLine

@Composable
fun WordDetailsSheet(
    word: String,
    contextLine: SubtitleLine?,
    translation: String?,
    isTranslating: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(word, style = MaterialTheme.typography.h4, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Context:", style = MaterialTheme.typography.overline)
        Text(contextLine?.textDe.orEmpty())
        Spacer(Modifier.height(16.dp))
        Text("Translation:", style = MaterialTheme.typography.overline)
        if (isTranslating) CircularProgressIndicator(Modifier.size(24.dp))
        else Text(translation ?: "Translation unavailable")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, enabled = translation != null && contextLine != null && !isSaving && !isTranslating) {
            Text(if (isSaving) "SAVING…" else "ADD TO DICTIONARY")
        }
    }
}