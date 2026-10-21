package com.langkraft.ui.player

import com.langkraft.audio.AudioPlayer
import com.langkraft.audio.AudioPlayerError
import com.langkraft.audio.AudioErrorReason
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
    fun missingLocalAudioWaitsForExplicitOnlineActionWithoutChangingSavedContent() = runTest {
        val f = fixture()
        f.vm.onEvent(PlayerEvent.LoadContent("content"))
        runCurrent()
        f.audio.fail(AudioErrorReason.LOCAL_ASSET_UNAVAILABLE)
        runCurrent()
        assertTrue(f.vm.state.value.canPlayOnline)
        assertEquals(content.localAudioPath, f.audio.loadedUrl)
        assertEquals(1, f.audio.loads.size)
        f.vm.onEvent(PlayerEvent.PlayPause)
        assertFalse(f.audio.isPlaying.value)
        f.vm.onEvent(PlayerEvent.PlayOnline)
        runCurrent()
        assertEquals(content.audioUrl, f.audio.loadedUrl)
        assertTrue(f.audio.isPlaying.value)
        assertTrue(f.vm.state.value.isUsingRemoteAudio)
        assertFalse(f.vm.state.value.canPlayOnline)
        assertNull(f.vm.state.value.error)
        assertEquals(content.localAudioPath, f.vm.state.value.content!!.localAudioPath)
        assertTrue(f.savedContents.isEmpty())
        f.vm.onEvent(PlayerEvent.PlayOnline)
        assertEquals(2, f.audio.loads.size)
    }

    @Test
    fun remoteFailureDoesNotLoopAndExplicitRetryKeepsRemoteSource() = runTest {
        val f = fixture()
        f.vm.onEvent(PlayerEvent.LoadContent("content"))
        runCurrent()
        f.audio.fail(AudioErrorReason.LOCAL_ASSET_UNAVAILABLE)
        runCurrent()
        f.vm.onEvent(PlayerEvent.PlayOnline)
        f.audio.fail(AudioErrorReason.SOURCE_ERROR)
        runCurrent()
        assertEquals("Audio failure", f.vm.state.value.error)
        assertFalse(f.vm.state.value.canPlayOnline)
        assertEquals(2, f.audio.loads.size)
        f.vm.onEvent(PlayerEvent.PlayOnline)
        runCurrent()
        assertEquals(2, f.audio.loads.size)
        f.vm.retryLoad()
        runCurrent()
        assertEquals(content.audioUrl, f.audio.loadedUrl)
        assertEquals(3, f.audio.loads.size)
        assertTrue(f.savedContents.isEmpty())
    }

    @Test
    fun staleAudioFailureCannotAffectLoadingOrNewContent() = runTest {
        val next = CompletableDeferred<ImmersionContent>()
        val f = fixture(load = { if (it == "next") next.await() else content })
        f.vm.onEvent(PlayerEvent.LoadContent("content"))
        runCurrent()
        val oldRequest = f.audio.requestId
        f.vm.onEvent(PlayerEvent.LoadContent("next"))
        f.audio.fail(AudioErrorReason.LOCAL_ASSET_UNAVAILABLE, oldRequest)
        runCurrent()
        assertTrue(f.vm.state.value.isLoading)
        assertNull(f.vm.state.value.error)
        assertFalse(f.vm.state.value.canPlayOnline)
        next.complete(content.copy(id = "next"))
        runCurrent()
        f.audio.fail(AudioErrorReason.SOURCE_ERROR, oldRequest)
        runCurrent()
        assertEquals("next", f.vm.state.value.content!!.id)
        assertNull(f.vm.state.value.error)
        assertFalse(f.vm.state.value.canPlayOnline)
    }

    @Test
    fun lateLocalFailureDoesNotReplaceSuccessfulRemoteLoad() = runTest {
        val f = fixture()
        f.vm.onEvent(PlayerEvent.LoadContent("content"))
        runCurrent()
        val oldRequest = f.audio.requestId
        f.audio.fail(AudioErrorReason.LOCAL_ASSET_UNAVAILABLE)
        runCurrent()
        f.vm.onEvent(PlayerEvent.PlayOnline)
        f.audio.fail(AudioErrorReason.LOCAL_ASSET_UNAVAILABLE, oldRequest)
        runCurrent()
        assertNull(f.vm.state.value.error)
        assertFalse(f.vm.state.value.canPlayOnline)
        assertTrue(f.vm.state.value.isUsingRemoteAudio)
    }

    @Test
    fun autoplayAndTemporaryStorageErrorsDoNotOfferOnlineFallback() = runTest {
        for (reason in listOf(AudioErrorReason.AUTOPLAY_BLOCKED, AudioErrorReason.STORAGE_ERROR)) {
            val f = fixture()
            f.vm.onEvent(PlayerEvent.LoadContent("content"))
            runCurrent()
            f.audio.fail(reason)
            runCurrent()
            assertNotNull(f.vm.state.value.error)
            assertFalse(f.vm.state.value.canPlayOnline)
            f.vm.onEvent(PlayerEvent.PlayOnline)
            assertEquals(1, f.audio.loads.size)
            assertEquals(content.localAudioPath, f.audio.loadedUrl)
            assertTrue(f.savedContents.isEmpty())
            f.vm.release()
        }
    }

    @Test
    fun remoteOnlyContentCannotOfferFallbackToTheSameSource() = runTest {
        val f = fixture(load = { content.copy(localAudioPath = null) })
        f.vm.onEvent(PlayerEvent.LoadContent("content"))
        runCurrent()
        f.audio.fail(AudioErrorReason.LOCAL_ASSET_UNAVAILABLE)
        runCurrent()
        assertTrue(f.vm.state.value.isUsingRemoteAudio)
        assertFalse(f.vm.state.value.canPlayOnline)
        f.vm.onEvent(PlayerEvent.PlayOnline)
        assertEquals(1, f.audio.loads.size)
    }

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
        fixture.vm.onEvent(PlayerEvent.LoadContent("content"))
        runCurrent()
        fixture.audio.error.value = AudioPlayerError(fixture.audio.requestId,
            AudioErrorReason.SOURCE_ERROR, "Audio download failed")
        runCurrent()
        assertEquals("Audio download failed", fixture.vm.state.value.error)
    }

    private fun TestScope.fixture(
        load: suspend (String) -> ImmersionContent? = { content },
        translate: suspend (String, String) -> String = { _, _ -> "translation" },
        save: suspend (VocabularyWord) -> Unit = {}
    ): Fixture {
        val audio = FakeAudioPlayer()
        val savedContents = mutableListOf<ImmersionContent>()
        val repository = object : ContentRepository {
            override fun getAllContent() = flowOf(listOf(content))
            override suspend fun getContentById(id: String) = load(id)
            override suspend fun saveContent(content: ImmersionContent) { savedContents += content }
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
        return Fixture(PlayerViewModel(repository, vocabulary, assistant, backgroundScope, audio), audio, savedContents)
    }

    private data class Fixture(val vm: PlayerViewModel, val audio: FakeAudioPlayer,
        val savedContents: List<ImmersionContent>)

    private class FakeAudioPlayer : AudioPlayer {
        override val currentTimeMs = MutableStateFlow(0L)
        override val isPlaying = MutableStateFlow(false)
        override val error = MutableStateFlow<AudioPlayerError?>(null)
        val loads = mutableListOf<Pair<String, Long>>()
        val requestId get() = loads.last().second
        var loadedUrl: String? = null
        var released = false
        override fun load(url: String, requestId: Long) {
            loads += url to requestId
            loadedUrl = url
            currentTimeMs.value = 0
            error.value = null
        }
        fun fail(reason: AudioErrorReason, requestId: Long = this.requestId) {
            error.value = AudioPlayerError(requestId, reason, "Audio failure")
        }
        override fun play() { isPlaying.value = true }
        override fun pause() { isPlaying.value = false }
        override fun seekTo(timeMs: Long) { currentTimeMs.value = timeMs }
        override fun release() { released = true }
    }
}
