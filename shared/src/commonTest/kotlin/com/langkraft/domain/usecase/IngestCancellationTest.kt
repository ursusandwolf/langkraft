package com.langkraft.domain.usecase

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class IngestCancellationTest {
    private val content = ImmersionContent("video", "Title", "/video.mp3", null, "https://youtu.be/video", 1)
    private class Local(private val cancel: Boolean = false) : LocalContentRepository {
        var saves = 0
        var saved: ImmersionContent? = null
        override fun getAllContent() = emptyFlow<List<ImmersionContent>>()
        override suspend fun getContentById(id: String): ImmersionContent? = null
        override fun getImmersionStats() = emptyFlow<ImmersionStats>()
        override suspend fun saveContent(content: ImmersionContent) {
            if (cancel) throw CancellationException("save cancelled")
            saves++
            saved = content
        }
    }
    private fun remote(fetch: suspend () -> ImmersionContent) = object : RemoteContentSource {
        override suspend fun fetchFromYouTube(url: String) = fetch()
    }

    @Test fun cancellationFromRemotePropagatesWithoutSaving() = runTest {
        val local = Local()
        val useCase = IngestContentUseCase(remote { throw CancellationException("cancel") }, local)
        assertFailsWith<CancellationException> { useCase("url") }
        assertEquals(0, local.saves)
    }

    @Test fun cancellationFromDatabasePropagates() = runTest {
        val useCase = IngestContentUseCase(remote { content }, Local(cancel = true))
        assertFailsWith<CancellationException> { useCase("url") }
    }

    @Test fun ordinaryFailureRemainsAResult() = runTest {
        val failure = IllegalStateException("network error")
        val useCase = IngestContentUseCase(remote { throw failure }, Local())
        assertSame(failure, useCase("url").exceptionOrNull())
    }

    @Test fun remoteFilesystemAndDownloadStateAreNotClientOfflineState() = runTest {
        for (status in listOf(DownloadStatus.IDLE, DownloadStatus.COMPLETED)) {
            val remoteContent = content.copy(localAudioPath = "/backend/downloads/video.mp3", downloadStatus = status)
            val local = Local()
            val result = IngestContentUseCase(remote { remoteContent }, local)("url").getOrThrow()
            val expected = remoteContent.copy(localAudioPath = null, downloadStatus = DownloadStatus.IDLE)
            assertEquals(expected, local.saved)
            assertEquals(expected, result)
            assertEquals("/backend/downloads/video.mp3", remoteContent.localAudioPath)
        }
    }
}
