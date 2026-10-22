package com.langkraft.data

import com.langkraft.data.audio.FileAudioDownloadCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileAudioDownloadCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun instancesShareOneContentLeaseButDifferentContentsRemainIndependent() = runBlocking {
        val directory = File(folder.root, "locks")
        val desktop = FileAudioDownloadCoordinator(directory)
        val tui = FileAudioDownloadCoordinator(directory)
        val first = assertNotNull(desktop.tryAcquire("video"))
        val other = assertNotNull(tui.tryAcquire("other"))
        try { assertNull(tui.tryAcquire("video")) }
        finally { first.release(); other.release() }
        first.release() // Idempotent; must not release somebody else's next lease.
        val next = assertNotNull(tui.tryAcquire("video"))
        try { first.release(); assertNull(desktop.tryAcquire("video")) }
        finally { next.release() }
    }

    @Test fun lockIsVisibleToAnotherJvmAndContentionDoesNotReleaseTheOwner() = runBlocking {
        val directory = File(folder.root, "locks")
        val desktop = FileAudioDownloadCoordinator(directory)
        val first = assertNotNull(desktop.tryAcquire("video"))
        val probe = NativeFileLockProbe(folder.root)
        try {
            val lockFile = directory.listFiles()!!.single()
            repeat(2) {
                assertNull(FileAudioDownloadCoordinator(directory).tryAcquire("video"))
                val child = probe.start(lockFile)
                try { assertEquals("BUSY", probe.ready(child)) }
                finally { probe.stop(child) }
            }
        } finally { first.release() }
        val child = probe.start(directory.listFiles()!!.single())
        try { assertEquals("HELD", probe.ready(child)) }
        finally { probe.stop(child) }
    }

    @Test fun failedAcquisitionDoesNotPoisonFutureAttempts() = runBlocking {
        val directory = folder.newFile("not-a-directory")
        val coordinator = FileAudioDownloadCoordinator(directory)
        assertFailsWith<IllegalStateException> { coordinator.tryAcquire("video") }
        check(directory.delete())
        val lease = assertNotNull(coordinator.tryAcquire("video"))
        lease.release()
    }

    @Test fun contentIdsCannotEscapeTheLockDirectoryAndNamespacesAreIndependent() = runBlocking {
        val firstDirectory = File(folder.root, "first")
        val first = assertNotNull(FileAudioDownloadCoordinator(firstDirectory).tryAcquire("../../outside"))
        val second = assertNotNull(FileAudioDownloadCoordinator(File(folder.root, "second")).tryAcquire("../../outside"))
        try {
            val lock = firstDirectory.listFiles()!!.single()
            assertEquals(firstDirectory.canonicalFile, lock.parentFile.canonicalFile)
            assertTrue(Regex("[a-f0-9]{64}\\.lock").matches(lock.name))
        } finally { first.release(); second.release() }
    }

    @Test fun cancellationWhileReturningLeaseReleasesItsChannel() = runTest {
        val caller = QueuedDispatcher()
        val directory = File(folder.root, "locks")
        val coordinator = FileAudioDownloadCoordinator(directory, StandardTestDispatcher(testScheduler))
        val operation = backgroundScope.launch(caller) {
            coordinator.tryAcquire("video")?.release()
        }
        caller.drain()
        runCurrent()
        assertNull(FileAudioDownloadCoordinator(directory).tryAcquire("video"))
        operation.cancel()
        repeat(3) { caller.drain(); runCurrent() }
        operation.join()
        val next = assertNotNull(FileAudioDownloadCoordinator(directory).tryAcquire("video"))
        next.release()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
