package com.langkraft.data

import com.langkraft.data.audio.FileAudioStorage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileAudioStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun completeDownloadReturnsAPersistentFile() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val storage = FileAudioStorage(folder.root, StandardTestDispatcher(testScheduler))
        val location = storage.store("content", ByteReadChannel(bytes), bytes.size.toLong())
        assertContentEquals(bytes, File(location).readBytes())
        assertFalse(location.endsWith(".part"))
    }

    @Test
    fun truncatedAndEmptyDownloadsLeaveNoFiles() = runTest {
        val storage = FileAudioStorage(folder.root, StandardTestDispatcher(testScheduler))
        assertFailsWith<IOException> {
            storage.store("content", ByteReadChannel(byteArrayOf(1)), 10)
        }
        assertFailsWith<IOException> {
            storage.store("content", ByteReadChannel(byteArrayOf()), null)
        }
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationDeletesPartialFileAndKeepsExistingAudio() = runTest {
        val storage = FileAudioStorage(folder.root, StandardTestDispatcher(testScheduler))
        val existing = storage.store("content", ByteReadChannel(byteArrayOf(9)), 1)
        val channel = ByteChannel(autoFlush = true)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            started.complete(Unit)
            storage.store("content", channel, null)
        }
        started.await()
        channel.writeFully(byteArrayOf(1, 2))
        runCurrent()
        assertTrue(folder.root.listFiles().orEmpty().any { it.name.endsWith(".part") })
        job.cancel()
        job.join()
        assertContentEquals(byteArrayOf(9), File(existing).readBytes())
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        channel.cancel(null)
    }

    @Test
    fun cancellationWhileReturningPublishedLocationDeletesTheUnownedFile() = runTest {
        val caller = QueuedDispatcher()
        val storage = FileAudioStorage(folder.root, StandardTestDispatcher(testScheduler))
        val operation = backgroundScope.launch(caller) {
            storage.store("content", ByteReadChannel(byteArrayOf(1, 2)), 2)
        }
        caller.drain()
        runCurrent()
        assertTrue(folder.root.listFiles().orEmpty().any { it.extension == "mp3" })
        operation.cancel()
        repeat(3) { caller.drain(); runCurrent() }
        operation.join()
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    @Test
    fun pathTraversalInContentIdIsRejected() = runTest {
        val storage = FileAudioStorage(folder.root, StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalArgumentException> {
            storage.store("../outside", ByteReadChannel(byteArrayOf(1)), 1)
        }
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }
}
