package com.langkraft.domain.usecase

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.ContentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IngestContentUseCaseTest {
    private val content = ImmersionContent("id", "Title", "https://host/audio.opus",
        null, "https://youtu.be/video", 10)

    @Test
    fun savesRemoteContentAndThenLocalAudioPath() = runTest {
        val repository = FakeRepository()
        val result = IngestContentUseCase(repository)(content.sourceUrl).getOrThrow()
        assertEquals("/offline/audio.opus", result.localAudioPath)
        assertEquals(listOf(content, result), repository.saved)
    }

    @Test
    fun cancellationDuringDownloadIsNotConvertedToFailure() = runTest {
        val repository = FakeRepository { throw CancellationException("Cancelled") }
        assertFailsWith<CancellationException> {
            IngestContentUseCase(repository)(content.sourceUrl)
        }
        assertEquals(listOf(content), repository.saved)
    }

    @Test
    fun downloadFailureReturnsFailureAndKeepsRemoteContent() = runTest {
        val repository = FakeRepository { error("Network unavailable") }
        val result = IngestContentUseCase(repository)(content.sourceUrl)
        assertTrue(result.isFailure)
        assertEquals(listOf(content), repository.saved)
    }

    private inner class FakeRepository(
        private val download: suspend () -> String = { "/offline/audio.opus" }
    ) : ContentRepository {
        val saved = mutableListOf<ImmersionContent>()
        override fun getAllContent() = flowOf(listOf(content))
        override suspend fun getContentById(id: String) = content
        override suspend fun saveContent(content: ImmersionContent) { saved += content }
        override suspend fun downloadAudio(content: ImmersionContent) = download()
        override suspend fun fetchFromYouTube(url: String) = content
    }
}
