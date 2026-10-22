package com.langkraft.data.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative per-content OS lease shared by Desktop and TUI using one data directory. */
class FileAudioDownloadCoordinator(
    private val directory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AudioDownloadCoordinator {
    private companion object {
        // Never open a second channel for a file held by this JVM: on some OSes
        // closing that channel can release the first channel's native lock too.
        val claimed = ConcurrentHashMap.newKeySet<String>()
    }

    override suspend fun tryAcquire(contentId: String): AudioDownloadLease? {
        var allocated: AudioDownloadLease? = null
        try {
            return withContext(dispatcher) {
                val root = directory.canonicalFile
                check(root.isDirectory || root.mkdirs()) { "Cannot create download lock directory" }
                val hash = MessageDigest.getInstance("SHA-256").digest(contentId.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val file = File(root, "$hash.lock").canonicalFile
                require(file.parentFile == root) { "Download lock is outside storage" }
                val key = file.path
                if (!claimed.add(key)) return@withContext null
                var channel: FileChannel? = null
                try {
                    val opened = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                    channel = opened
                    val lock = try { opened.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) {
                        opened.close()
                        claimed.remove(key)
                        return@withContext null
                    }
                    val released = AtomicBoolean(false)
                    object : AudioDownloadLease {
                        private val nativeLock = requireNotNull(lock)
                        override val recoveryProtocol = "jvm-file-lock-v1"
                        override suspend fun release() = withContext(NonCancellable + dispatcher) {
                            if (released.compareAndSet(false, true)) {
                                var failure: Throwable? = null
                                try { if (nativeLock.isValid) nativeLock.release() }
                                catch (error: Throwable) { failure = error }
                                try { opened.close() }
                                catch (cleanup: Throwable) {
                                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                                } finally { if (!opened.isOpen) claimed.remove(key) }
                                failure?.let { throw it }
                            }
                        }
                    }.also { allocated = it }
                } catch (error: Throwable) {
                    try { channel?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    if (channel?.isOpen != true) claimed.remove(key)
                    throw error
                }
            }
        } catch (error: Throwable) {
            // Includes prompt cancellation while handing an acquired lease back
            // from IO. The caller must never lose ownership of an open channel.
            withContext(NonCancellable) {
                try { allocated?.release() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }
}
