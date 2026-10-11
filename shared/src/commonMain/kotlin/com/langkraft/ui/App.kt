package com.langkraft.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langkraft.audio.AudioPlayer
import com.langkraft.data.*
import com.langkraft.domain.ai.MockLinguisticAssistant
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.usecase.IngestContentUseCase
import com.langkraft.ui.player.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

class AppServices(
    val local: LocalDatabase,
    val client: HttpClient,
    val audioStore: AudioStore,
    val audioPlayer: AudioPlayer,
    val defaultBackendUrl: String,
) {
    fun close() {
        audioPlayer.release()
        audioStore.close()
        client.close()
        local.close()
    }
}

@Composable
fun LangkraftApp(services: AppServices) {
    val scope = rememberCoroutineScope()
    var backendUrl by remember { mutableStateOf(services.defaultBackendUrl) }
    var youtubeUrl by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val repository = remember(services) {
        SqlContentRepository(services.local, ContentRemote { url -> KtorContentRemote(services.client, backendUrl).ingest(url) }, services.audioStore)
    }
    val vocabulary = remember(services) { SqlVocabularyRepository(services.local) }
    val contentFlow = remember(repository) { repository.getAllContent().catch { error = it.message ?: "Cannot read library"; emit(emptyList()) } }
    val content by contentFlow.collectAsState(emptyList())
    val player = remember(services, repository) {
        PlayerViewModel(repository, vocabulary, MockLinguisticAssistant(), services.audioPlayer, scope, services.audioStore::resolve)
    }
    DisposableEffect(player) { onDispose { player.close() } }
    LaunchedEffect(selectedId, player) { selectedId?.let { player.onEvent(PlayerEvent.LoadContent(it)) } }

    MaterialTheme {
        if (selectedId != null) {
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = { services.audioPlayer.pause(); selectedId = null }) { Text("BACK TO LIBRARY") }
                ImmersionPlayerView(player, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Langkraft", style = MaterialTheme.typography.h4)
                Text("Learn German through listening")
                OutlinedTextField(backendUrl, { backendUrl = it }, label = { Text("Backend URL") }, enabled = !importing, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(youtubeUrl, { youtubeUrl = it }, label = { Text("YouTube video URL") }, enabled = !importing, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !importing && youtubeUrl.isNotBlank(), onClick = {
                    importing = true
                    error = null
                    scope.launch {
                        try {
                            IngestContentUseCase(repository)(youtubeUrl).fold(
                                onSuccess = { selectedId = it.id; youtubeUrl = "" },
                                onFailure = { error = it.message ?: "Import failed" },
                            )
                        } catch (cancelled: CancellationException) { throw cancelled }
                        finally { importing = false }
                    }
                }) { Text(if (importing) "IMPORTING AUDIO AND SUBTITLES…" else "IMPORT VIDEO") }
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colors.error) }
                if (content.isEmpty() && !importing) Text("Your library is empty. Import a video with German subtitles.")
                LazyColumn(Modifier.weight(1f)) {
                    items(content, key = { it.id }) { item ->
                        Column(Modifier.fillMaxWidth().clickable { selectedId = item.id }.padding(vertical = 12.dp)) {
                            Text(item.title, style = MaterialTheme.typography.h6)
                            Text("${item.durationSeconds / 60} min • ${item.subtitles.size} subtitles")
                            Text(if (item.localAudioPath != null) "Available offline" else "Download incomplete — import again to retry", style = MaterialTheme.typography.caption)
                        }
                    }
                }
            }
        }
    }
}
