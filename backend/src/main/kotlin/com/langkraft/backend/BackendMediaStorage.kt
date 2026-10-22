package com.langkraft.backend

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** One backend owns a downloads directory. Imports, readers and cleanup share this monitor. */
class BackendMediaStorage(
    downloadsDir: String,
    private val retentionMs: Long = 0,
    private val now: () -> Long = System::currentTimeMillis,
    val limits: MediaLimits = MediaLimits(),
    private val remove: (Path) -> Unit = { Files.delete(it) }
) {
    private val root = Path.of(downloadsDir).toAbsolutePath().normalize()
    private val imports = mutableSetOf<String>()
    private val readers = mutableMapOf<String, Int>()
    private val logger = LoggerFactory.getLogger(javaClass)

    init { require(retentionMs >= 0) { "media_retention_ms must be non-negative" } }

    data class CleanupResult(val deleted: List<String>, val failures: List<String>)
    class MediaLease(val file: File, private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }

    @Synchronized
    fun beginImport(jobId: String): AutoCloseable {
        require(isJobId(jobId)) { "Invalid job ID" }
        check(imports.add(jobId)) { "Import already active" }
        var createdDirectory: Path? = null
        try {
            Files.createDirectories(root)
            check(!Files.isSymbolicLink(root)) { "Downloads directory must not be a symlink" }
            if (limits.enabled) {
                if (!hasCapacity()) cleanup() // Reclaim only media already eligible for retention.
                if (!hasCapacity()) throw MediaCapacityException()
            }
            val directory = root.resolve(jobId)
            Files.createDirectory(directory)
            createdDirectory = directory
            Files.writeString(directory.resolve(MARKER), "pending\n${now()}", CREATE_NEW)
            if (limits.maxImportBytes > 0 && treeBytes(directory) > limits.maxImportBytes) throw MediaCapacityException()
            return once { synchronized(this) { imports.remove(jobId) } }
        } catch (failure: Exception) {
            // No process has started yet. Remove only the directory/marker created by this admission.
            createdDirectory?.let { directory ->
                try { Files.deleteIfExists(directory.resolve(MARKER)); Files.deleteIfExists(directory) }
                catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            imports.remove(jobId)
            throw failure
        }
    }

    @Synchronized
    fun publish(jobId: String, commitReady: () -> Unit = {}) {
        check(jobId in imports) { "Import lease required" }
        checkImportBudget(jobId)
        val directory = safeDirectory(jobId) ?: error("Missing import directory")
        // The marker remains on disk across restart. Unknown/damaged markers are preserved.
        Files.writeString(directory.resolve(MARKER), "ready\n${now()}")
        // Readers enter only after READY is committed, without a transient 404 after polling.
        commitReady()
        imports.remove(jobId)
    }

    @Synchronized
    fun acquireMedia(segments: List<String>): MediaLease? {
        if (segments.size !in 1..2 || segments.any { !Regex("[A-Za-z0-9_.-]+").matches(it) || it == "." || it == ".." }) return null
        val extension = segments.last().substringAfterLast('.', "").lowercase()
        if (extension !in setOf("mp3", "ogg", "opus", "wav", "m4a") || Files.isSymbolicLink(root)) return null
        val key = segments.first()
        val file = segments.fold(root) { path, segment -> path.resolve(segment) }
        if (segments.size == 2) {
            val directory = safeDirectory(key) ?: return null
            val marker = directory.resolve(MARKER)
            if (Files.exists(marker, NOFOLLOW_LINKS) && (readMarker(directory)?.first != "ready" || extension != "mp3")) return null
            if (key in imports) return null
        }
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return null
        readers[key] = (readers[key] ?: 0) + 1
        val release = once {
            synchronized(this) {
                val remaining = readers.getValue(key) - 1
                if (remaining == 0) readers.remove(key) else readers[key] = remaining
            }
        }
        return MediaLease(file.toFile()) { release.close() }
    }

    @Synchronized
    fun discardImport(jobId: String) {
        require(jobId in imports) { "Import lease required" }
        check((readers[jobId] ?: 0) == 0) { "Media is being served" }
        val directory = safeDirectory(jobId) ?: return
        check(readMarker(directory) != null) { "Unmanaged import directory" }
        deleteOwnedTree(directory)
    }

    @Synchronized
    fun importDirectory(jobId: String): Path {
        check(jobId in imports) { "Import lease required" }
        return safeDirectory(jobId) ?: error("Missing import directory")
    }

    @Synchronized
    fun checkImportBudget(jobId: String) {
        if (!limits.enabled || jobId !in imports) return
        val bytes = treeBytes(importDirectory(jobId))
        if (limits.maxImportBytes > 0 && bytes > limits.maxImportBytes) throw MediaLimitException()
        if (limits.maxTotalBytes > 0 && accountedUsage() > limits.maxTotalBytes) {
            throw MediaLimitException("Import exceeds configured total media budget")
        }
    }

    @Synchronized
    fun retainAudio(jobId: String, audio: File) {
        val directory = importDirectory(jobId)
        val keep = audio.toPath().toAbsolutePath().normalize()
        check(keep.parent == directory && Files.isRegularFile(keep, NOFOLLOW_LINKS)) { "Invalid published audio" }
        val paths = Files.walk(directory).use { it.toList() }
        check(paths.none { Files.isSymbolicLink(it) }) { "Symlink in media directory" }
        for (path in paths.sortedByDescending { it.nameCount }) {
            if (path != directory && path != directory.resolve(MARKER) && path != keep) remove(path)
        }
    }

    private fun hasCapacity(): Boolean {
        if (limits.maxTotalBytes > 0 && accountedUsage() > limits.maxTotalBytes) return false
        val remaining = imports.fold(0L) { total, id ->
            Math.addExact(total, (limits.maxImportBytes - treeBytes(root.resolve(id))).coerceAtLeast(0))
        }
        return Files.getFileStore(root).usableSpace >= remaining
    }

    /** Active directories cost at least their full reservation, never their small initial size. */
    private fun accountedUsage(): Long {
        var bytes = treeBytes(root, skipImports = true)
        for (id in imports) bytes = Math.addExact(bytes, maxOf(limits.maxImportBytes, treeBytes(root.resolve(id))))
        return bytes
    }

    private fun treeBytes(path: Path, skipImports: Boolean = false): Long {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return 0
        var bytes = 0L
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (skipImports && dir.parent == root && dir.fileName.toString() in imports) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) bytes = Math.addExact(bytes, attrs.size())
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                // yt-dlp/ffmpeg rename and delete temporary files while the monitor samples them.
                if (exc is java.nio.file.NoSuchFileException) return FileVisitResult.CONTINUE
                throw exc
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null && exc !is java.nio.file.NoSuchFileException) throw exc
                return FileVisitResult.CONTINUE
            }
        })
        return bytes
    }

    @Synchronized
    fun cleanup(): CleanupResult {
        val deleted = mutableListOf<String>()
        val failures = mutableListOf<String>()
        if (retentionMs == 0L || !Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return CleanupResult(deleted, failures)
        val cutoff = now() - retentionMs
        Files.newDirectoryStream(root).use { children ->
            for (child in children) {
                val id = child.fileName.toString()
                if (!isJobId(id) || id in imports || (readers[id] ?: 0) > 0) continue
                try {
                    val directory = safeDirectory(id) ?: continue
                    val (_, timestamp) = readMarker(directory) ?: continue
                    if (timestamp > cutoff) continue
                    deleteOwnedTree(directory)
                    deleted += id
                } catch (failure: Exception) {
                    failures += id
                    logger.warn("Cannot remove expired media job {}", id, failure)
                }
            }
        }
        return CleanupResult(deleted, failures)
    }

    private fun safeDirectory(id: String): Path? {
        val path = root.resolve(id).normalize()
        return path.takeIf { it.parent == root && !Files.isSymbolicLink(root) && Files.isDirectory(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
    }

    private fun readMarker(directory: Path): Pair<String, Long>? {
        val file = directory.resolve(MARKER)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file) > 128) return null
        val parts = Files.readString(file).split('\n')
        if (parts.size != 2 || parts[0] !in listOf("pending", "ready")) return null
        val timestamp = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return parts[0] to timestamp
    }

    private fun deleteOwnedTree(directory: Path) {
        check(directory.parent == root && safeDirectory(directory.fileName.toString()) == directory)
        val marker = directory.resolve(MARKER)
        val paths = Files.walk(directory).use { it.toList() }
        // Reject the whole tree before deleting anything if ownership is ambiguous.
        check(paths.none { Files.isSymbolicLink(it) }) { "Symlink in media directory" }
        for (path in paths.sortedByDescending { it.nameCount }) {
            if (path != directory && path != marker) remove(path)
        }
        val ownership = Files.readString(marker)
        remove(marker) // Last file: a partial failure retains proof of ownership for retry.
        try { remove(directory) } catch (failure: Exception) {
            try { Files.writeString(marker, ownership, CREATE_NEW) }
            catch (restore: Exception) { failure.addSuppressed(restore) }
            throw failure
        }
    }

    private fun isJobId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun once(action: () -> Unit): AutoCloseable {
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) action() }
    }

    companion object { private const val MARKER = ".langkraft-media-v1" }
}
