package com.langkraft.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.ai.DeepAnalysisResult
import com.langkraft.ui.components.WaveformVisualizer

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
            TopAppBar(
                title = { Text(state.content?.title ?: "Langkraft") },
                actions = {
                    IconButton(onClick = { viewModel.onEvent(PlayerEvent.ToggleOffline) }) {
                        val status = state.content?.downloadStatus ?: DownloadStatus.IDLE
                        when (status) {
                            DownloadStatus.DOWNLOADING -> {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                            }
                            DownloadStatus.COMPLETED -> {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Downloaded",
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                            DownloadStatus.ERROR -> {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = Color.Red
                                )
                            }
                            DownloadStatus.IDLE -> {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Download",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            PlayerControlBar(
                isPlaying = state.isPlaying,
                isLooping = state.isLooping,
                currentSpeed = state.playbackSpeed,
                onPlayPause = { viewModel.onEvent(PlayerEvent.PlayPause) },
                onToggleLoop = { viewModel.onEvent(PlayerEvent.ToggleLoop) },
                onSpeedChange = { viewModel.onEvent(PlayerEvent.SetPlaybackSpeed(it)) }
            )
        }
    ) { padding ->
        Box(modifier = modifier.padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Waveform visualization at the top
                state.content?.let { content ->
                    val progress = if (content.durationSeconds > 0) {
                        state.currentTimeMs.toFloat() / (content.durationSeconds * 1000)
                    } else 0f

                    WaveformVisualizer(
                        amplitudes = content.waveform.ifEmpty { List(50) { 0.2f + (it % 5) * 0.1f } },
                        progress = progress,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.fillMaxSize().wrapContentSize())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f)
                    ) {
                        state.content?.subtitles?.let { subtitles ->
                            items(subtitles) { line ->
                                SubtitleRow(
                                    line = line,
                                    isCurrent = state.currentTimeMs in line.startMs..line.endMs,
                                    translation = state.sentenceTranslations[line.id],
                                    lemmas = state.lemmatizedSentences[line.id],
                                    isTranslating = state.analyzingSentenceId == line.id,
                                    isLemmatizing = state.lemmatizingSentenceId == line.id,
                                    onClick = { viewModel.onEvent(PlayerEvent.SeekTo(line.startMs)) },
                                    onWordClick = { viewModel.onEvent(PlayerEvent.WordClicked(it, line)) },
                                    onTranslateClick = { viewModel.onEvent(PlayerEvent.ToggleTranslation(line)) },
                                    onLemmatizeClick = { viewModel.onEvent(PlayerEvent.ToggleLemmatization(line)) },
                                    onAnalysisClick = { viewModel.onEvent(PlayerEvent.DeepAnalysisClicked(line)) },
                                    onMemorizationClick = { viewModel.onEvent(PlayerEvent.MemorizationClicked(line.originalText)) }
                                )
                            }
                        }
                    }
                }
            }

            // Word Details Sheet
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
                                isSaving = false,
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

            state.error?.let { message ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = {
                        TextButton(onClick = { /* Clear error */ }) {
                            Text("OK", color = Color.Yellow)
                        }
                    }
                ) {
                    Text(message)
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
    lemmas: Map<String, String>?,
    isTranslating: Boolean,
    isLemmatizing: Boolean,
    onClick: () -> Unit,
    onWordClick: (String) -> Unit,
    onTranslateClick: () -> Unit,
    onLemmatizeClick: () -> Unit,
    onAnalysisClick: () -> Unit,
    onMemorizationClick: () -> Unit
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
            Row(modifier = Modifier.weight(1f).padding(end = 8.dp), horizontalArrangement = Arrangement.Start) {
                line.originalText.split(Regex("\\s+")).forEach { word ->
                    val cleanWord = word.filter { it.isLetter() }
                    val lemma = lemmas?.get(word) ?: lemmas?.get(cleanWord)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = word,
                            modifier = Modifier
                                .clickable { onWordClick(cleanWord) }
                                .padding(horizontal = 2.dp),
                            style = MaterialTheme.typography.h6.copy(
                                color = if (isCurrent) MaterialTheme.colors.primary else Color.Unspecified
                            )
                        )
                        if (lemma != null && lemma.lowercase() != word.lowercase()) {
                            Text(
                                text = lemma,
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.secondary,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }
            }

            Row {
                IconButton(onClick = onTranslateClick, modifier = Modifier.size(24.dp)) {
                    if (isTranslating) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("文", style = MaterialTheme.typography.body2) 
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onLemmatizeClick, modifier = Modifier.size(24.dp)) {
                    if (isLemmatizing) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("L", style = MaterialTheme.typography.body2, fontWeight = FontWeight.Bold) 
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onAnalysisClick, modifier = Modifier.size(24.dp)) {
                    Text("⚙", style = MaterialTheme.typography.body2) 
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onMemorizationClick, modifier = Modifier.size(24.dp)) {
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
        title = { 
            Text(
                "Deep Grammatical Analysis",
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Card(
                    elevation = 2.dp,
                    backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.05f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = analysis.syntaxExplanation,
                        style = MaterialTheme.typography.body1,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(analysis.words) { word ->
                        Card(
                            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                            elevation = 1.dp
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = word.original,
                                        style = MaterialTheme.typography.subtitle1,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colors.primary
                                    )
                                    Text(
                                        text = word.lemma,
                                        style = MaterialTheme.typography.subtitle2,
                                        color = MaterialTheme.colors.secondary
                                    )
                                }
                                Text(
                                    text = word.grammaticalInfo,
                                    style = MaterialTheme.typography.body2
                                )
                                Text(
                                    text = "Role: ${word.roleInSentence}",
                                    style = MaterialTheme.typography.caption,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.padding(8.dp)
            ) { 
                Text("VERSTANDEN") 
            }
        },
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
fun PlayerControlBar(
    isPlaying: Boolean,
    isLooping: Boolean,
    currentSpeed: Float,
    onPlayPause: () -> Unit,
    onToggleLoop: () -> Unit,
    onSpeedChange: (Float) -> Unit
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
            
            val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f)
            val nextSpeed = speeds[(speeds.indexOf(currentSpeed) + 1) % speeds.size]
            
            Button(onClick = { onSpeedChange(nextSpeed) }) {
                Text("${currentSpeed}x", style = MaterialTheme.typography.body2)
            }
        }
    }
}

