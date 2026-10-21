package com.langkraft.ui.player

import com.langkraft.audio.AudioPlayer
import com.langkraft.domain.ai.LinguisticAssistant
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.repository.ContentRepository
import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val line = SubtitleLine("line", "content", 0, 1000, "Guten Tag", null)
    private val content = ImmersionContent("content", "Title", "https://host/audio.opus",
        "/offline/audio.opus", "https://youtu.be/video", 1, listOf(line))
    private val word = VocabularyWord("word", "Tag", null, "day", line.textDe, "content", line.id)

    @Test
    fun loadsOfflineAudioAndUsesEngineStateForPlaybackAndSeek() = runTest {
        val fixture = fixture()
        fixture.vm.onEvent(PlayerEvent.LoadContent("content"))
        runCurrent()
        assertEquals(content.localAudioPath, fixture.audio.loadedUrl)
        fixture.vm.onEvent(PlayerEvent.PlayPause)
        runCurrent()
        assertTrue(fixture.vm.state.value.isPlaying)
        fixture.vm.onEvent(PlayerEvent.SeekTo(500))
        runCurrent()
        assertEquals(500, fixture.vm.state.value.currentTimeMs)
        fixture.audio.isPlaying.value = false // End of track / external pause.
        runCurrent()
        assertFalse(fixture.vm.state.value.isPlaying)
        fixture.vm.release()
        assertTrue(fixture.audio.released)
    }

    @Test
    fun loadFailureStopsSpinnerAndCanBeRetried() = runTest {
        var fail = true
        val fixture = fixture(load = { if (fail) error("Database unavailable") else content })
        fixture.vm.onEvent(PlayerEvent.LoadContent("content"))
        runCurrent()
        assertFalse(fixture.vm.state.value.isLoading)
        assertEquals("Database unavailable", fixture.vm.state.value.error)
        fail = false
        fixture.vm.retryLoad()
        runCurrent()
        assertEquals(content, fixture.vm.state.value.content)
        assertNull(fixture.vm.state.value.error)
    }

    @Test
    fun staleLoadCannotReplaceNewContent() = runTest {
        val first = CompletableDeferred<ImmersionContent>()
        val fixture = fixture(load = { id ->
            if (id == "first") withContext(NonCancellable) { first.await() } else content
        })
        fixture.vm.onEvent(PlayerEvent.LoadContent("first"))
        runCurrent()
        fixture.vm.onEvent(PlayerEvent.LoadContent("second"))
        runCurrent()
        first.complete(content.copy(id = "first"))
        runCurrent()
        assertEquals("content", fixture.vm.state.value.content?.id)
        assertEquals(content.localAudioPath, fixture.audio.loadedUrl)
    }

    @Test
    fun staleTranslationCannotOverwriteNewSelection() = runTest {
        val first = CompletableDeferred<String>()
        val fixture = fixture(translate = { word, _ ->
            if (word == "Guten") withContext(NonCancellable) { first.await() } else "day"
        })
        fixture.vm.onEvent(PlayerEvent.WordClicked("Guten", line))
        runCurrent()
        fixture.vm.onEvent(PlayerEvent.WordClicked("Tag", line))
        assertNull(fixture.vm.state.value.translation)
        runCurrent()
        assertEquals("day", fixture.vm.state.value.translation)
        first.complete("good")
        runCurrent()
        assertEquals("Tag", fixture.vm.state.value.selectedWord)
        assertEquals("day", fixture.vm.state.value.translation)
    }

    @Test
    fun dismissDuringTranslationClearsLoadingAndIgnoresLateResult() = runTest {
        val response = CompletableDeferred<String>()
        val fixture = fixture(translate = { _, _ -> withContext(NonCancellable) { response.await() } })
        fixture.vm.onEvent(PlayerEvent.WordClicked("Tag", line))
        runCurrent()
        fixture.vm.onEvent(PlayerEvent.DismissWordDetails)
        response.complete("day")
        runCurrent()
        assertNull(fixture.vm.state.value.selectedWord)
        assertNull(fixture.vm.state.value.translation)
        assertFalse(fixture.vm.state.value.isTranslating)
    }

    @Test
    fun translationFailureStopsSpinnerAndRetryRecovers() = runTest {
        var fail = true
        val fixture = fixture(translate = { _, _ -> if (fail) error("Offline") else "day" })
        fixture.vm.onEvent(PlayerEvent.WordClicked("Tag", line))
        runCurrent()
        assertFalse(fixture.vm.state.value.isTranslating)
        assertEquals("Offline", fixture.vm.state.value.wordError)
        fail = false
        fixture.vm.retryTranslation()
        runCurrent()
        assertEquals("day", fixture.vm.state.value.translation)
        assertNull(fixture.vm.state.value.wordError)
    }

    @Test
    fun duplicateSaveIsIgnoredAndCompletionDoesNotDismissAnotherWord() = runTest {
        val saved = CompletableDeferred<Unit>()
        var writes = 0
        val fixture = fixture(save = { writes++; saved.await() })
        fixture.vm.onEvent(PlayerEvent.WordClicked("Tag", line))
        runCurrent()
        fixture.vm.saveWord(word)
        fixture.vm.saveWord(word)
        runCurrent()
        assertEquals(1, writes)
        assertTrue(fixture.vm.state.value.isSaving)
        fixture.vm.onEvent(PlayerEvent.WordClicked("Guten", line))
        runCurrent()
        saved.complete(Unit)
        runCurrent()
        assertEquals("Guten", fixture.vm.state.value.selectedWord)
        assertFalse(fixture.vm.state.value.isSaving)
    }

    @Test
    fun failedSaveKeepsSelectionAndAllowsRetry() = runTest {
        var fail = true
        val fixture = fixture(save = { if (fail) error("Disk full") })
        fixture.vm.onEvent(PlayerEvent.WordClicked("Tag", line))
        runCurrent()
        fixture.vm.saveWord(word)
        runCurrent()
        assertEquals("Tag", fixture.vm.state.value.selectedWord)
        assertFalse(fixture.vm.state.value.isSaving)
        assertNotNull(fixture.vm.state.value.wordError)
        fail = false
        fixture.vm.saveWord(word)
        runCurrent()
        assertNull(fixture.vm.state.value.selectedWord)
    }

    @Test
    fun audioEngineFailureIsVisibleToTheUser() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.audio.error.value = "Audio download failed"
        runCurrent()
        assertEquals("Audio download failed", fixture.vm.state.value.error)
    }

    private fun TestScope.fixture(
        load: suspend (String) -> ImmersionContent? = { content },
        translate: suspend (String, String) -> String = { _, _ -> "translation" },
        save: suspend (VocabularyWord) -> Unit = {}
    ): Fixture {
        val audio = FakeAudioPlayer()
        val repository = object : ContentRepository {
            override fun getAllContent() = flowOf(listOf(content))
            override suspend fun getContentById(id: String) = load(id)
            override suspend fun saveContent(content: ImmersionContent) = Unit
            override suspend fun downloadAudio(content: ImmersionContent) = "/local"
            override suspend fun fetchFromYouTube(url: String) = content
        }
        val vocabulary = object : VocabularyRepository {
            override fun getWordsToReview() = flowOf(emptyList<VocabularyWord>())
            override suspend fun saveWord(word: VocabularyWord) = save(word)
            override suspend fun deleteWord(id: String) = Unit
        }
        val assistant = object : LinguisticAssistant {
            override suspend fun translateWithContext(word: String, context: String) = translate(word, context)
        }
        return Fixture(PlayerViewModel(repository, vocabulary, assistant, backgroundScope, audio), audio)
    }

    private data class Fixture(val vm: PlayerViewModel, val audio: FakeAudioPlayer)

    private class FakeAudioPlayer : AudioPlayer {
        override val currentTimeMs = MutableStateFlow(0L)
        override val isPlaying = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        var loadedUrl: String? = null
        var released = false
        override fun load(url: String) { loadedUrl = url; currentTimeMs.value = 0 }
        override fun play() { isPlaying.value = true }
        override fun pause() { isPlaying.value = false }
        override fun seekTo(timeMs: Long) { currentTimeMs.value = timeMs }
        override fun release() { released = true }
    }
}
