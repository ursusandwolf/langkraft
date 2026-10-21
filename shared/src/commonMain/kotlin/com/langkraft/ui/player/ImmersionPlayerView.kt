package com.langkraft.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun ImmersionPlayerView(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll logic
    val currentIndex = state.content?.subtitles?.indexOfFirst {
        state.currentTimeMs >= it.startMs && state.currentTimeMs < it.endMs
    } ?: -1
    LaunchedEffect(state.content?.id, currentIndex) {
        if (currentIndex != -1 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(state.content?.title ?: "Langkraft") })
        },
        bottomBar = {
            PlayerControlBar(
                isPlaying = state.isPlaying,
                enabled = state.content != null && !state.isLoading && state.error == null,
                onPlayPause = { viewModel.onEvent(PlayerEvent.PlayPause) }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colors.error, modifier = Modifier.padding(16.dp))
                Row {
                    if (state.canPlayOnline) {
                        TextButton(onClick = { viewModel.onEvent(PlayerEvent.PlayOnline) }) {
                            Text("Воспроизвести по сети")
                        }
                    }
                    TextButton(onClick = viewModel::retryLoad) { Text("RETRY") }
                }
            }
            if (state.isUsingRemoteAudio && state.content != null) {
                Text("Аудио по сети", modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.caption)
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.fillMaxSize().wrapContentSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    state.content?.subtitles?.let { subtitles ->
                        items(subtitles) { line ->
                            SubtitleRow(
                                line = line,
                                isCurrent = state.currentTimeMs >= line.startMs && state.currentTimeMs < line.endMs,
                                onClick = { viewModel.onEvent(PlayerEvent.SeekTo(line.startMs)) },
                                onWordClick = { word -> viewModel.onEvent(PlayerEvent.WordClicked(word, line)) }
                            )
                        }
                    }
                }
            }
        }
    }

    state.selectedWord?.let { word ->
        Dialog(onDismissRequest = { viewModel.onEvent(PlayerEvent.DismissWordDetails) }) {
            Surface {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    WordDetailsSheet(
                        word = word,
                        contextLine = state.selectedWordContext,
                        translation = state.translation,
                        isSaving = state.isSaving,
                        onSave = viewModel::saveWord,
                        onDismiss = { viewModel.onEvent(PlayerEvent.DismissWordDetails) },
                        isTranslating = state.isTranslating,
                        error = state.wordError,
                        onRetry = viewModel::retryTranslation
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleRow(
    line: SubtitleLine,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onWordClick: (String) -> Unit
) {
    val backgroundColor by animateColorAsState(
        if (isCurrent) MaterialTheme.colors.primary.copy(alpha = 0.15f)
        else Color.Transparent
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        // Simple tokenization for now
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            line.textDe.split(" ").forEach { word ->
                Text(
                    text = word,
                    modifier = Modifier
                        .clickable { onWordClick(word) }
                        .padding(horizontal = 2.dp),
                    style = MaterialTheme.typography.h6.copy(
                        color = if (isCurrent) MaterialTheme.colors.primary else Color.Unspecified
                    )
                )
            }
        }
    }
}

@Composable
fun PlayerControlBar(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    enabled: Boolean = true
) {
    Surface(elevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = onPlayPause, enabled = enabled) {
                Text(if (isPlaying) "PAUSE" else "PLAY")
            }
        }
    }
}
