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

import androidx.compose.foundation.shape.RoundedCornerShape
import com.langkraft.domain.ai.DeepAnalysisResult

@Composable
fun ImmersionPlayerView(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val scaffoldState = rememberScaffoldState()

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
        scaffoldState = scaffoldState,
        topBar = {
            TopAppBar(title = { Text(state.content?.title ?: "Langkraft") })
        },
        bottomBar = {
            PlayerControlBar(
                isPlaying = state.isPlaying,
                isLooping = state.isLooping,
                onPlayPause = { viewModel.onEvent(PlayerEvent.PlayPause) },
                onToggleLoop = { viewModel.onEvent(PlayerEvent.ToggleLoop) }
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
                                translation = state.sentenceTranslations[line.id],
                                isTranslating = state.analyzingSentenceId == line.id,
                                onClick = { viewModel.onEvent(PlayerEvent.SeekTo(line.startMs)) },
                                onWordClick = { word -> viewModel.onEvent(PlayerEvent.WordClicked(word, line)) },
                                onTranslateClick = { viewModel.onEvent(PlayerEvent.ToggleTranslation(line)) },
                                onAnalyzeClick = { viewModel.onEvent(PlayerEvent.DeepAnalysisClicked(line)) },
                                onMemorizeClick = { viewModel.onEvent(PlayerEvent.MemorizationClicked(line.textDe)) }
                            )
                        }
                    }
                }
            }

            // Word Details Sheet (Simple Modal)
            state.selectedWord?.let { word ->
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                            elevation = 16.dp
                        ) {
                            WordDetailsSheet(
                                word = word,
                                contextLine = state.selectedWordContext,
                                result = state.wordTranslation,
                                isSaving = false, // TODO: handle saving state
                                onSave = { viewModel.saveWord(it) },
                                onDismiss = { viewModel.onEvent(PlayerEvent.DismissWordDetails) }
                            )
                        }
                    }
                }
            }

            // Deep Analysis Dialog
            state.deepAnalysis?.let { analysis ->
                DeepAnalysisDialog(
                    analysis = analysis,
                    onDismiss = { viewModel.onEvent(PlayerEvent.DismissDeepAnalysis) }
                )
            }

            // Memorization Tool
            state.memorizationText?.let { text ->
                MemorizationDialog(
                    text = text,
                    onDismiss = { viewModel.onEvent(PlayerEvent.DismissMemorization) }
                )
            }
            
            if (state.isAnalyzing) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.3f)
                ) {
                    CircularProgressIndicator(modifier = Modifier.wrapContentSize())
                }
            }
        }
    }
}

@Composable
fun SubtitleRow(
    line: SubtitleLine,
    isCurrent: Boolean,
    translation: String?,
    isTranslating: Boolean,
    onClick: () -> Unit,
    onWordClick: (String) -> Unit,
    onTranslateClick: () -> Unit,
    onAnalyzeClick: () -> Unit,
    onMemorizeClick: () -> Unit
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Text tokens
            Row(modifier = Modifier.weight(1f).padding(end = 8.dp), horizontalArrangement = Arrangement.Start) {
                // Tokenize by word
                line.textDe.split(Regex("\\s+")).forEach { word ->
                    Text(
                        text = word,
                        modifier = Modifier
                            .clickable { onWordClick(word.filter { it.isLetter() }) }
                            .padding(horizontal = 2.dp),
                        style = MaterialTheme.typography.h6.copy(
                            color = if (isCurrent) MaterialTheme.colors.primary else Color.Unspecified
                        )
                    )
                }
            }

            // Quick Actions
            Row {
                IconButton(onClick = onTranslateClick, modifier = Modifier.size(24.dp)) {
                    if (isTranslating) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("文", style = MaterialTheme.typography.body2) 
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onAnalyzeClick, modifier = Modifier.size(24.dp)) {
                    Text("⚙", style = MaterialTheme.typography.body2) 
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onMemorizeClick, modifier = Modifier.size(24.dp)) {
                    Text("🧠", style = MaterialTheme.typography.body2) 
                }
            }
        }

        if (translation != null) {
            Text(
                text = translation,
                style = MaterialTheme.typography.body2,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun DeepAnalysisDialog(
    analysis: DeepAnalysisResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deep Grammatical Analysis") },
        text = {
            LazyColumn {
                item {
                    Text(text = analysis.syntaxExplanation, style = MaterialTheme.typography.body1)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                }
                items(analysis.words) { word ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(text = word.original, style = MaterialTheme.typography.subtitle1, color = MaterialTheme.colors.primary)
                        Text(text = "Base: ${word.lemma}", style = MaterialTheme.typography.caption)
                        Text(text = "Info: ${word.grammaticalInfo}", style = MaterialTheme.typography.body2)
                        Text(text = "Role: ${word.roleInSentence}", style = MaterialTheme.typography.body2, color = Color.Gray)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("CLOSE") }
        }
    )
}

@Composable
fun PlayerControlBar(
    isPlaying: Boolean,
    isLooping: Boolean,
    onPlayPause: () -> Unit,
    onToggleLoop: () -> Unit
) {
    Surface(elevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleLoop) {
                Text(
                    text = "🔁", 
                    style = MaterialTheme.typography.h6,
                    color = if (isLooping) MaterialTheme.colors.primary else Color.Gray
                )
            }
            
            Button(onClick = onPlayPause) {
                Text(if (isPlaying) "PAUSE" else "PLAY")
            }
            
            // Placeholder for speed control
            Text("1.0x", style = MaterialTheme.typography.body2, color = Color.Gray)
        }
    }
}
