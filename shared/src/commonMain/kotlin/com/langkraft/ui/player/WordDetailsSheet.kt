package com.langkraft.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langkraft.domain.ai.TranslationResult
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.ui.components.NativeTextInput
import com.langkraft.io.randomUUID
import kotlinx.datetime.Clock

@Composable
fun WordDetailsSheet(
    word: String,
    contextLine: SubtitleLine?,
    result: TranslationResult?,
    isSaving: Boolean,
    onSave: (VocabularyWord) -> Unit,
    onDismiss: () -> Unit,
    isTranslating: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    aiAvailable: Boolean = false
) {
    val wordId = remember(word, contextLine?.id) { randomUUID() }
    var translation by remember(wordId) { mutableStateOf("") }
    var previousSuggestion by remember(wordId) { mutableStateOf("") }
    LaunchedEffect(result) {
        // A slow suggestion must never replace text already edited by the user.
        result?.let {
            if (translation == previousSuggestion) translation = it.translation
            previousSuggestion = it.translation
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(word, style = MaterialTheme.typography.h4)
            IconButton(onClick = onDismiss) { Text("✕") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Context:", style = MaterialTheme.typography.overline)
        Text(contextLine?.originalText.orEmpty(), style = MaterialTheme.typography.body1)
        Spacer(Modifier.height(16.dp))
        NativeTextInput(
            value = translation, onValueChange = { translation = it },
            label = "Translation", enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        )
        if (isTranslating) {
            Text("AI suggestion is loading. You can enter your own translation.")
        } else if (aiAvailable) {
            TextButton(onClick = onRetry, enabled = !isSaving) { Text("SUGGEST WITH AI") }
        }
        result?.explanation?.let { Text(it, style = MaterialTheme.typography.body2) }
        error?.let { Text(it, color = MaterialTheme.colors.error) }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                contextLine?.let { line ->
                    if (translation.isNotBlank()) onSave(VocabularyWord(
                        id = wordId, word = word, lemma = result?.lemma,
                        translation = translation.trim(), contextSentence = line.originalText,
                        contentId = line.contentId, subtitleLineId = line.id,
                        addedAt = Clock.System.now().toEpochMilliseconds()
                    ))
                }
            },
            enabled = translation.isNotBlank() && contextLine != null && !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isSaving) "SAVING…" else "ADD TO DICTIONARY") }
    }
}
