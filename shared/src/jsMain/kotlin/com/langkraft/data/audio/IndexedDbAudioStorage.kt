package com.langkraft.data.audio

import com.langkraft.data.storage.abortAudio
import com.langkraft.data.storage.appendAudio
import com.langkraft.data.storage.beginAudio
import com.langkraft.data.storage.commitAudio
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class IndexedDbAudioStorage : AudioStorage {
    override suspend fun delete(location: String) {
        require(location.startsWith("indexeddb-audio:"))
        abortAudio(location.removePrefix("indexeddb-audio:")).await()
    }
    override suspend fun store(
        contentId: String,
        source: ByteReadChannel,
        expectedBytes: Long?
    ): String {
        // Allocate under NonCancellable so the cleanup token cannot be lost.
        val token = withContext(NonCancellable) { beginAudio(contentId).await() }
        try {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = source.readAvailable(buffer, 0, buffer.size)
                if (count == -1) break
                appendAudio(token, buffer.copyOf(count)).await()
            }
            coroutineContext.ensureActive()
            val location = commitAudio(token, expectedBytes?.toDouble()).await()
            coroutineContext.ensureActive()
            return location
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try { abortAudio(token).await() }
                catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }
}
