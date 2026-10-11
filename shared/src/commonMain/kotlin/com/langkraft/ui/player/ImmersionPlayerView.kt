package com.langkraft.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.langkraft.domain.model.SubtitleLine

@Composable
fun ImmersionPlayerView(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val currentIndex = state.content?.subtitles?.indexOfFirst { state.currentTimeMs >= it.startMs && state.currentTimeMs < it.endMs } ?: -1
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && !listState.isScrollInProgress) listState.animateScrollToItem(currentIndex)
    }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(state.content?.title ?: "Langkraft") }) },
        bottomBar = {
            Surface(elevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    Button(enabled = state.content != null && !state.isLoading, onClick = { viewModel.onEvent(PlayerEvent.PlayPause) }) {
                        Text(if (state.isPlaying) "PAUSE" else "PLAY")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { Text(it, color = MaterialTheme.colors.error, modifier = Modifier.padding(16.dp)) }
            if (state.isLoading) CircularProgressIndicator(Modifier.padding(16.dp))
            else LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.content?.subtitles.orEmpty(), key = { it.id }) { line ->
                    SubtitleRow(line, state.currentTimeMs >= line.startMs && state.currentTimeMs < line.endMs,
                        { viewModel.onEvent(PlayerEvent.SeekTo(line.startMs)) },
                        { word -> viewModel.onEvent(PlayerEvent.WordClicked(word, line)) })
                }
            }
        }
    }
    state.selectedWord?.let { word ->
        Dialog(onDismissRequest = { viewModel.onEvent(PlayerEvent.DismissWordDetails) }) {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column {
                    state.error?.let { Text(it, color = MaterialTheme.colors.error, modifier = Modifier.padding(16.dp)) }
                    WordDetailsSheet(word, state.selectedWordContext, state.translation, state.isTranslating, state.isSaving,
                        viewModel::saveSelectedWord, { viewModel.onEvent(PlayerEvent.DismissWordDetails) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleRow(line: SubtitleLine, isCurrent: Boolean, onClick: () -> Unit, onWordClick: (String) -> Unit) {
    val color by animateColorAsState(if (isCurrent) MaterialTheme.colors.primary.copy(alpha = 0.15f) else Color.Transparent)
    FlowRow(Modifier.fillMaxWidth().background(color).clickable(onClick = onClick).padding(16.dp)) {
        line.textDe.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
            Text(token, style = MaterialTheme.typography.h6,
                modifier = Modifier.clickable { onWordClick(token.trim { !it.isLetterOrDigit() }) }.padding(horizontal = 3.dp))
        }
    }
}