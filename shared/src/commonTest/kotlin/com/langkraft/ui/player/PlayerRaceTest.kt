package com.langkraft.ui.player

import com.langkraft.audio.*
import com.langkraft.di.AppConfig
import com.langkraft.domain.ai.*
import com.langkraft.domain.model.*
import com.langkraft.domain.repository.*
import com.langkraft.io.FileSystem
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRaceTest {
    @Test fun disabledAiAllowsManualSaveWithoutAnyTranslationRequest() = runTest {
        var saved: VocabularyWord? = null
        val vm = fixture(capabilities = { AiCapabilities(false) },
            translate = { _, _ -> error("AI must not be called") }, save = { saved = it })
        try {
            vm.onEvent(PlayerEvent.LoadContent("video")); runCurrent()
            vm.onEvent(PlayerEvent.WordClicked("Tag", line)); runCurrent()
            assertFalse(vm.currentState.aiAvailable)
            assertFalse(vm.currentState.isTranslatingWord)
            assertNull(vm.currentState.wordTranslation)
            vm.saveWord(word.copy(translation = "day")); runCurrent()
            assertEquals("day", saved?.translation)
            assertEquals("Guten Tag", saved?.contextSentence)
        } finally { vm.onCleared() }
    }

    @Test fun unavailableBackendCapabilitiesStillAllowsLocalPlaybackAndManualSave() = runTest {
        val vm = fixture(capabilities = { error("Offline") })
        try {
            vm.onEvent(PlayerEvent.LoadContent("video")); runCurrent()
            assertEquals("video", vm.currentState.content?.id)
            assertFalse(vm.currentState.isLoading)
            assertFalse(vm.currentState.aiAvailable)
            assertNull(vm.currentState.error)
        } finally { vm.onCleared() }
    }

    @Test fun failedAiRequestDoesNotPreventManualSave() = runTest {
        var saved: VocabularyWord? = null
        val vm = fixture(translate = { _, _ -> error("Provider unavailable") }, save = { saved = it })
        try {
            vm.onEvent(PlayerEvent.WordClicked("Tag", line)); runCurrent()
            assertEquals("Provider unavailable", vm.currentState.wordError)
            vm.saveWord(word.copy(translation = "day")); runCurrent()
            assertEquals("day", saved?.translation)
        } finally { vm.onCleared() }
    }
    private val line = SubtitleLine("cue", "video", 0, 1000, "Guten Tag", null)
    private val content = ImmersionContent("video", "Title", "/audio.mp3", null, "https://youtu.be/video", 1)
    private val word = VocabularyWord("word", "Tag", contextSentence="Guten Tag")

    @Test fun lateTranslationCannotReplaceAnotherSelection() = runTest {
        val first = CompletableDeferred<TranslationResult>()
        val vm = fixture(translate = { text, _ -> if(text == "Guten") withContext(NonCancellable) { first.await() } else TranslationResult("day", "Tag", "noun") })
        try {
            vm.onEvent(PlayerEvent.WordClicked("Guten", line)); runCurrent()
            vm.onEvent(PlayerEvent.WordClicked("Tag", line)); runCurrent()
            first.complete(TranslationResult("good", "gut", "adjective")); runCurrent()
            assertEquals("day", vm.currentState.wordTranslation?.translation)
            assertEquals("Tag", vm.currentState.selectedWord)
        } finally { vm.onCleared() }
    }

    @Test fun dismissIgnoresUncancellableTranslation() = runTest {
        val response = CompletableDeferred<TranslationResult>()
        val vm = fixture(translate = { _, _ -> withContext(NonCancellable) { response.await() } })
        try {
            vm.onEvent(PlayerEvent.WordClicked("Tag", line)); runCurrent()
            vm.onEvent(PlayerEvent.DismissWordDetails)
            response.complete(TranslationResult("day", "Tag", "noun")); runCurrent()
            assertNull(vm.currentState.wordTranslation)
            assertFalse(vm.currentState.isTranslatingWord)
        } finally { vm.onCleared() }
    }

    @Test fun duplicateSaveDoesNotDismissNewSelection() = runTest {
        val response = CompletableDeferred<Unit>(); var writes = 0
        val vm = fixture(save = { writes++; response.await() })
        try {
            vm.onEvent(PlayerEvent.WordClicked("Tag", line)); runCurrent()
            vm.saveWord(word); vm.saveWord(word); runCurrent()
            assertEquals(1, writes); assertTrue(vm.currentState.isSavingWord)
            vm.onEvent(PlayerEvent.WordClicked("Guten", line)); runCurrent()
            response.complete(Unit); runCurrent()
            assertEquals("Guten", vm.currentState.selectedWord)
            assertFalse(vm.currentState.isSavingWord)
        } finally { vm.onCleared() }
    }

    @Test fun failedSaveKeepsSelectionAndAllowsRetry() = runTest {
        var fail = true
        val vm = fixture(save = { if(fail) error("Disk full") })
        try {
            vm.onEvent(PlayerEvent.WordClicked("Tag", line)); runCurrent()
            vm.saveWord(word); runCurrent()
            assertEquals("Disk full", vm.currentState.wordError)
            assertFalse(vm.currentState.isSavingWord)
            fail = false; vm.saveWord(word); runCurrent()
            assertNull(vm.currentState.selectedWord)
        } finally { vm.onCleared() }
    }

    @Test fun lateContentLoadCannotReplaceNewContent() = runTest {
        val old = CompletableDeferred<ImmersionContent>()
        val vm = fixture(load = { if(it == "old") withContext(NonCancellable) { old.await() } else content })
        try {
            vm.onEvent(PlayerEvent.LoadContent("old")); runCurrent()
            vm.onEvent(PlayerEvent.LoadContent("video")); runCurrent()
            old.complete(content.copy(id="old")); runCurrent()
            assertEquals("video", vm.currentState.content?.id)
            assertFalse(vm.currentState.isLoading)
        } finally { vm.onCleared() }
    }

    @Test fun staleAudioErrorCannotAffectNewSource() = runTest {
        val audio = FakeAudio()
        val vm = fixture(audio=audio)
        try {
            vm.onEvent(PlayerEvent.LoadContent("video")); runCurrent()
            val first = audio.request
            vm.onEvent(PlayerEvent.LoadContent("video")); runCurrent()
            audio.error.value=AudioPlayerError(first, AudioErrorReason.SOURCE_ERROR, "Old failure"); runCurrent()
            assertNull(vm.currentState.error)
            audio.error.value=AudioPlayerError(audio.request, AudioErrorReason.SOURCE_ERROR, "Current failure"); runCurrent()
            assertEquals("Current failure", vm.currentState.error)
        } finally { vm.onCleared() }
    }

    private fun TestScope.fixture(
        translate: suspend (String,String)->TranslationResult = { word, _ -> TranslationResult("translation",word,"noun") },
        save: suspend (VocabularyWord)->Unit = {},
        load: suspend (String)->ImmersionContent? = { content },
        audio: FakeAudio = FakeAudio(),
        capabilities: suspend () -> AiCapabilities = { AiCapabilities(true) }
    ): PlayerViewModel {
        val local=object:LocalContentRepository {
            override fun getAllContent()=flowOf(listOf(content))
            override suspend fun getContentById(id:String)=load(id)
            override suspend fun saveContent(content:ImmersionContent) {}
            override fun getImmersionStats()=flowOf(ImmersionStats(1,1))
        }
        val vocabulary=object:VocabularyRepository {
            override fun getWordsToReview()=flowOf(emptyList<VocabularyWord>())
            override fun getReviewCount()=flowOf(0)
            override suspend fun saveWord(word:VocabularyWord)=save(word)
            override suspend fun deleteWord(id:String) {}
            override suspend fun sync(lastSyncTimestamp:Long)=0L
            override fun getWordCountsByStatus()=flowOf(emptyMap<WordStatus,Long>())
            override fun getWordsAddedSince(timestamp:Long)=flowOf(0L)
            override suspend fun getSyncMetadata(key:String):String?=null
            override suspend fun setSyncMetadata(key:String,value:String) {}
        }
        val assistant=object:LinguisticAssistant by MockLinguisticAssistant() {
            override suspend fun capabilities() = capabilities()
            override suspend fun translateWord(word:String,context:String)=translate(word,context)
        }
        val files=object:FileSystem {
            override fun getAppDataDir()=""
            override fun exists(path:String)=false
            override fun delete(path:String) {}
            override fun writeBytes(path:String,bytes:ByteArray) {}
            override fun resolve(base:String,child:String)=child
            override fun rename(from:String,to:String) {}
        }
        return PlayerViewModel(local,object:AudioDownloader { override suspend fun downloadAudio(content:ImmersionContent)="local" },vocabulary,assistant,audio,files,AppConfig(Url("http://localhost")),StandardTestDispatcher(testScheduler))
    }

    private class FakeAudio:AudioPlayer {
        var request=0L
        override val currentTimeMs=MutableStateFlow(0L)
        override val isPlaying=MutableStateFlow(false)
        override val error=MutableStateFlow<AudioPlayerError?>(null)
        override fun load(url:String,requestId:Long) { request=requestId;error.value=null }
        override fun play() { isPlaying.value=true }
        override fun pause() { isPlaying.value=false }
        override fun seekTo(timeMs:Long) { currentTimeMs.value=timeMs }
        override fun setPlaybackSpeed(speed:Double) {}
        override fun release() {}
    }
}
