package com.langkraft.data

import com.langkraft.data.audio.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class JournaledFileAudioStorageTest {
    @get:Rule val folder = TemporaryFolder()
    private val audio get() = File(folder.root, "audio")
    private fun coordinator() = FileAudioDownloadCoordinator(File(folder.root, "locks"))

    @Test fun writingRequiresLeaseAndHandledFailureLeavesNoPayloadOrJournal() = runBlocking {
        val owner = coordinator()
        val storage = JournaledFileAudioStorage(audio, owner)
        assertFailsWith<IllegalStateException> { storage.store("video", ByteReadChannel(byteArrayOf(1)), 1) }
        assertTrue(audio.listFiles().orEmpty().isEmpty())
        val lease = assertNotNull(owner.tryAcquire("video"))
        try {
            assertFails { storage.store("video", ByteReadChannel(byteArrayOf(1)), 20) }
            assertTrue(audio.listFiles().orEmpty().isEmpty())
        } finally { lease.release() }
    }

    @Test fun publicationHasJournalUntilConfirmationAndConfirmedFilesAreNotCandidates() = runBlocking {
        val owner = coordinator()
        val storage = JournaledFileAudioStorage(audio, owner)
        val lease = assertNotNull(owner.tryAcquire("video"))
        try {
            val location = storage.store("video", ByteReadChannel(byteArrayOf(1, 2)), 2)
            assertEquals(listOf(PendingAudioAsset(location, "video", "jvm-file-lock-v1")), storage.listPending())
            assertTrue(File(location + ".pending").isFile)
            storage.confirmLinked(location)
            assertTrue(storage.listPending().isEmpty())
            assertFalse(File(location + ".pending").exists())
            assertContentEquals(byteArrayOf(1, 2), File(location).readBytes())
            storage.confirmLinked(location)
        } finally { lease.release() }
    }

    @Test fun journalPrecedesPartialAndCancellationCleansBoth() = runTest {
        val owner = coordinator()
        val lease = assertNotNull(owner.tryAcquire("video"))
        val storage = JournaledFileAudioStorage(audio, owner, StandardTestDispatcher(testScheduler))
        val channel = ByteChannel(autoFlush = true)
        try {
            val writer = launch { storage.store("video", channel, null) }
            runCurrent()
            val candidate = storage.listPending().single()
            assertTrue(File(candidate.location + ".pending").isFile)
            assertTrue(File(candidate.location + ".part").isFile)
            assertFalse(File(candidate.location).exists())
            writer.cancelAndJoin()
            assertTrue(audio.listFiles().orEmpty().isEmpty())
        } finally { channel.cancel(null); lease.release() }
    }

    @Test fun cleanupNeedsLeaseAndIsIdempotent() = runBlocking {
        val owner = coordinator()
        val storage = JournaledFileAudioStorage(audio, owner)
        val lease = assertNotNull(owner.tryAcquire("video"))
        val location = try { storage.store("video", ByteReadChannel(byteArrayOf(1)), 1) }
        finally { lease.release() }
        assertFailsWith<IllegalStateException> { storage.resolvePending(location, false) }
        assertTrue(File(location).isFile)
        val recovery = assertNotNull(owner.tryAcquire("video"))
        try {
            assertTrue(storage.resolvePending(location, false))
            assertFalse(storage.resolvePending(location, false))
            assertTrue(audio.listFiles().orEmpty().isEmpty())
        } finally { recovery.release() }
    }

    @Test fun partialCleanupFailureRetainsJournalAndNextAttemptFinishesIt() = runBlocking {
        val owner = coordinator()
        val storage = JournaledFileAudioStorage(audio, owner)
        val lease = assertNotNull(owner.tryAcquire("video"))
        try {
            val location = storage.store("video", ByteReadChannel(byteArrayOf(1)), 1)
            val obstacle = File(location + ".part").apply { check(mkdir()) }
            assertFails { storage.resolvePending(location, false) }
            assertFalse(File(location).exists())
            assertTrue(File(location + ".pending").isFile)
            assertEquals(location, storage.listPending().single().location)
            check(obstacle.delete())
            assertTrue(storage.resolvePending(location, false))
            assertTrue(audio.listFiles().orEmpty().isEmpty())
        } finally { lease.release() }
    }

    @Test fun unknownMalformedAndLegacyArtifactsAreNotAutomaticCandidates() = runBlocking {
        check(audio.mkdirs())
        val legacy = FileAudioStorage(audio).store("legacy", ByteReadChannel(byteArrayOf(7)), 1)
        File(legacy + ".part").writeBytes(byteArrayOf(8))
        File(legacy + ".pending").writeText("""{"version":99,"contentId":"legacy","fileName":"${File(legacy).name}"}""")
        val malformed = File(audio, "malformed.pending").apply { writeText("{") }
        val outside = folder.newFile("outside.mp3").apply { writeBytes(byteArrayOf(9)) }
        val badName = File(audio, "bad.pending").apply {
            writeText("""{"version":1,"contentId":"video","fileName":"../outside.mp3"}""")
        }
        val storage = JournaledFileAudioStorage(audio, coordinator())
        assertTrue(storage.listPending().isEmpty())
        assertContentEquals(byteArrayOf(7), File(legacy).readBytes())
        assertTrue(File(legacy + ".part").isFile)
        assertTrue(malformed.isFile && badName.isFile)
        assertContentEquals(byteArrayOf(9), outside.readBytes())
    }

    @Test fun referencedMissingPublicationKeepsJournalAndPartialForInvestigation() = runBlocking {
        check(audio.mkdirs())
        val file = File(audio, "video-${UUID.randomUUID()}.mp3")
        File(file.path + ".pending").writeText("""{"version":1,"contentId":"video","fileName":"${file.name}"}""")
        File(file.path + ".part").writeBytes(byteArrayOf(1))
        val owner = coordinator()
        val lease = assertNotNull(owner.tryAcquire("video"))
        try {
            assertFalse(JournaledFileAudioStorage(audio, owner).resolvePending(file.path, true))
            assertTrue(File(file.path + ".pending").isFile)
            assertTrue(File(file.path + ".part").isFile)
        } finally { lease.release() }
    }

    @Test fun cancellationWhileReturningPublishedFileCleansJournalToo() = runTest {
        val owner = coordinator()
        val lease = assertNotNull(owner.tryAcquire("video"))
        try {
            val caller = QueuedDispatcher()
            val storage = JournaledFileAudioStorage(audio, owner, StandardTestDispatcher(testScheduler))
            val operation = backgroundScope.launch(caller) { storage.store("video", ByteReadChannel(byteArrayOf(1)), 1) }
            caller.drain()
            runCurrent()
            assertTrue(audio.listFiles().orEmpty().any { it.extension == "mp3" })
            operation.cancel()
            repeat(5) { caller.drain(); runCurrent() }
            operation.join()
            assertTrue(audio.listFiles().orEmpty().isEmpty())
        } finally { lease.release() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
