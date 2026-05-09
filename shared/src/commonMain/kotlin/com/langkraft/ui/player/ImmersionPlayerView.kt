package com.langkraft.ui.player

import androidx.compose.animation.animateColorAsState
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
import com.langkraft.domain.model.SubtitleLine

@Composable
fun ImmersionPlayerView(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll logic
    LaunchedEffect(state.currentTimeMs) {
        val currentIndex = state.content?.subtitles?.indexOfFirst { 
            state.currentTimeMs in it.startMs..it.endMs 
        } ?: -1
        
        if (currentIndex != -1 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(state.content?.title ?: "Langkraft") })
        },
        bottomBar = {
            PlayerControlBar(
                isPlaying = state.isPlaying,
                onPlayPause = { viewModel.onEvent(PlayerEvent.PlayPause) }
            )
        }
    ) { padding ->
        Box(modifier = modifier.padding(padding)) {
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
                                isCurrent = state.currentTimeMs in line.startMs..line.endMs,
                                onClick = { viewModel.onEvent(PlayerEvent.SeekTo(line.startMs)) },
                                onWordClick = { word -> viewModel.onEvent(PlayerEvent.WordClicked(word, line)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

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
        Row(modifier = Modifier.fillMaxWidth()) {
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
    onPlayPause: () -> Unit
) {
    Surface(elevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = onPlayPause) {
                Text(if (isPlaying) "PAUSE" else "PLAY")
            }
        }
    }
}
