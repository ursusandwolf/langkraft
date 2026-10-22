package com.langkraft.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.CanvasBasedWindow
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.data.audio.IndexedDbAudioStorage
import com.langkraft.data.database.DatabaseHandle
import com.langkraft.data.database.createWebDatabase
import com.langkraft.data.remote.ContentApi
import com.langkraft.data.remote.createContentHttpClient
import com.langkraft.data.repository.SqlDelightContentRepository
import com.langkraft.data.repository.SqlDelightVocabularyRepository
import com.langkraft.domain.ai.MockLinguisticAssistant
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.usecase.IngestContentUseCase
import com.langkraft.ui.player.ImmersionPlayerView
import com.langkraft.ui.player.PlayerEvent
import com.langkraft.ui.player.PlayerViewModel
import io.ktor.client.engine.js.Js
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlin.js.Date
import org.jetbrains.skiko.wasm.onWasmReady

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    onWasmReady {
        document.getElementById("startup")?.remove()
        CanvasBasedWindow(canvasElementId = "ComposeTarget") {
            MaterialTheme(colors = lightColors(primary = Color(0xff176b52), secondary = Color(0xffaa6024))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xfff5f7f4)) { WebApp() }
            }
        }
    }
}

private class AppServices(val handle: DatabaseHandle) {
    private var closed = false
    val client = createContentHttpClient(Js)
    val contents = SqlDelightContentRepository(handle.access,
        ContentApi(client, window.location.origin), IndexedDbAudioStorage(), { Date.now().toLong() })
    val vocabulary = SqlDelightVocabularyRepository(handle.access, { Date.now().toLong() })
    val ingest = IngestContentUseCase(contents)
    fun close() {
        if (closed) return
        closed = true
        client.close()
        handle.close()
    }
}

@Composable
private fun WebApp() {
    var services by remember { mutableStateOf<AppServices?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            val handle = createWebDatabase()
            try {
                ensureActive()
                services = AppServices(handle)
            } catch (failure: Throwable) { handle.close(); throw failure }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Не удалось открыть хранилище" }
    }
    val current = services
    DisposableEffect(current) {
        val listener: (org.w3c.dom.events.Event) -> Unit = { event ->
            if (event.asDynamic().persisted != true) current?.close()
        }
        window.addEventListener("pagehide", listener)
        onDispose { window.removeEventListener("pagehide", listener); current?.close() }
    }
    if (current == null) {
        Column(Modifier.padding(24.dp)) {
            Text("Langkraft", style = MaterialTheme.typography.h4)
            if (error == null) { Text("Открываем библиотеку…"); LinearProgressIndicator() }
            else {
                Text("Не удалось открыть локальную базу: $error", color = MaterialTheme.colors.error)
                Button(onClick = { attempt++ }) { Text("Повторить") }
            }
        }
    } else LibraryApp(current)
}

@Composable
private fun LibraryApp(services: AppServices) {
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var library by remember { mutableStateOf<List<ImmersionContent>?>(null) }
    var words by remember { mutableStateOf<List<VocabularyWord>>(emptyList()) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var wordsError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showWords by remember { mutableStateOf(false) }
    LaunchedEffect(services, refresh) {
        libraryError = null
        try { services.contents.getAllContent().collect { library = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { libraryError = failure.message ?: "Ошибка чтения библиотеки" }
    }
    LaunchedEffect(services, refresh) {
        wordsError = null
        try { services.vocabulary.getWordsToReview().collect { words = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { wordsError = failure.message ?: "Ошибка чтения слов" }
    }
    val id = selectedId
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Langkraft") }, actions = {
            TextButton(onClick = { selectedId = null; showWords = false }) { Text("Библиотека", color = Color.White) }
            TextButton(onClick = { selectedId = null; showWords = true }) { Text("Слова", color = Color.White) }
        })
        if (id != null) {
            key(id) {
                val viewModel = remember { PlayerViewModel(services.contents, services.vocabulary,
                    MockLinguisticAssistant(), scope, AudioPlayerImpl()) }
                DisposableEffect(viewModel) { onDispose { viewModel.release() } }
                LaunchedEffect(viewModel) { viewModel.onEvent(PlayerEvent.LoadContent(id)) }
                Text("Демонстрационный перевод · нажмите слово для сохранения, строку для перемотки",
                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.caption)
                ImmersionPlayerView(viewModel, Modifier.weight(1f))
            }
        } else if (showWords) {
            Text("Слова для повторения", style = MaterialTheme.typography.h5, modifier = Modifier.padding(16.dp))
            wordsError?.let { Text(it, color = MaterialTheme.colors.error); TextButton(onClick = { refresh++ }) { Text("Повторить") } }
            if (words.isEmpty() && wordsError == null) Text("Сохраните слово из субтитров плеера.", Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(words, key = { it.id }) { word ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(word.word, style = MaterialTheme.typography.h6)
                            Text(word.translation.orEmpty())
                            Text(word.contextSentence, style = MaterialTheme.typography.body2)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 900.dp)) {
                Text("Слушайте. Понимайте. Запоминайте.", style = MaterialTheme.typography.h5)
                Spacer(Modifier.height(12.dp))
                NativeUrlInput(value = url, onValueChange = { url = it; importError = null },
                    enabled = !importing, modifier = Modifier.fillMaxWidth().height(56.dp))
                Row {
                    Button(enabled = !importing && url.isNotBlank(), onClick = {
                        val source = url.trim()
                        importing = true
                        importError = null
                        importJob = scope.launch {
                            try {
                                services.ingest(source).fold(onSuccess = {
                                    url = ""; selectedId = it.id
                                }, onFailure = {
                                    importError = "Не удалось импортировать видео. Проверьте ссылку, немецкие субтитры и доступность сервера. Можно повторить."
                                })
                            } finally { importing = false; importJob = null }
                        }
                    }) { Text(if (importing) "Импорт…" else "Импортировать") }
                    if (importing) TextButton(onClick = { importJob?.cancel() }) { Text("Отменить") }
                }
                if (importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Извлекаем субтитры и сохраняем аудио. Это может занять несколько минут.") }
                importError?.let { Text(it, color = MaterialTheme.colors.error) }
                Text("Библиотека", style = MaterialTheme.typography.h5, modifier = Modifier.padding(top = 20.dp))
                libraryError?.let { Text(it, color = MaterialTheme.colors.error); TextButton(onClick = { refresh++ }) { Text("Повторить") } }
                if (library == null && libraryError == null) LinearProgressIndicator()
                if (library?.isEmpty() == true) Text("Здесь появятся ваши видео. Добавьте первое по ссылке выше.")
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(library.orEmpty(), key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.title, style = MaterialTheme.typography.h6)
                            Text("${item.durationSeconds / 60} мин · ${item.subtitles.size} строк · " +
                                if (item.localAudioPath != null) "Аудио сохранено" else "Аудио по сети")
                            Button(onClick = { selectedId = item.id }) { Text("Открыть") }
                        }
                    }
                }
            }
        }
    }
}
