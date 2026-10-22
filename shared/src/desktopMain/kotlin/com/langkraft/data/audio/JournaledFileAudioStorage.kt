package com.langkraft.data.audio

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.charset.CharacterCodingException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** FileAudioStorage decorator; only known sidecars authorize automatic recovery. */
class JournaledFileAudioStorage(
    directory: File,
    private val coordinator: FileAudioDownloadCoordinator,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AudioStorage, PendingAudioStorage {
    private val root = directory.canonicalFile

    @Serializable
    private data class Marker(val version: Int, val contentId: String, val fileName: String)

    private fun payload(location: String): File {
        val file = File(location).absoluteFile
        require(file.parentFile == root && file.canonicalFile == file) { "Audio location is outside managed storage" }
        return file
    }
    private fun sidecar(file: File) = File(root, file.name + ".pending")
    private fun partial(file: File) = File(root, file.name + ".part")

    private fun readMarker(file: File): Marker? {
        val marker = try {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || Files.size(file.toPath()) > 4096) return null
            Json.decodeFromString<Marker>(Files.readString(file.toPath()))
        }
        catch (_: NoSuchFileException) { return null } // Another process acknowledged it.
        catch (_: CharacterCodingException) { return null }
        catch (_: SerializationException) { return null }
        if (marker.version != 1 || !Regex("[A-Za-z0-9_-]{1,128}").matches(marker.contentId)) return null
        val suffix = marker.fileName.removePrefix(marker.contentId + "-").removeSuffix(".mp3")
        val uuid = try { UUID.fromString(suffix) } catch (_: IllegalArgumentException) { return null }
        if (marker.fileName != "${marker.contentId}-$uuid.mp3" || file.name != marker.fileName + ".pending") return null
        return marker
    }

    private fun remove(file: File) {
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Managed audio path is not a regular file" }
        Files.deleteIfExists(file.toPath())
    }

    private suspend fun begin(contentId: String, location: String) = withContext(dispatcher) {
        check(coordinator.owns(contentId)) { "File publication requires its content lease" }
        val file = payload(location)
        val bytes = Json.encodeToString(Marker(1, contentId, file.name)).toByteArray(Charsets.UTF_8)
        // Payload creation starts only after a complete journal record was forced.
        FileChannel.open(sidecar(file).toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    override suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?): String {
        var allocated: String? = null
        val writer = FileAudioStorage(root, dispatcher) { id, location -> allocated = location; begin(id, location) }
        try { return writer.store(contentId, source, expectedBytes) }
        catch (error: Throwable) {
            withContext(NonCancellable + dispatcher) {
                try {
                    allocated?.let { location ->
                        val file = payload(location)
                        // FileAudioStorage already attempted compensation. Retain
                        // the journal if either payload may still need recovery.
                        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS) && Files.notExists(partial(file).toPath(), NOFOLLOW_LINKS)) {
                            remove(sidecar(file))
                        }
                    }
                } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    override suspend fun listPending(): List<PendingAudioAsset> = withContext(dispatcher) {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return@withContext emptyList()
        val files = root.listFiles() ?: throw IOException("Cannot read audio journal directory")
        files.filter { it.name.endsWith(".pending") }.mapNotNull { file ->
            val marker = readMarker(file) ?: return@mapNotNull null
            val audio = File(root, marker.fileName)
            // Never follow a replaced/symlinked payload outside the profile.
            if (audio.canonicalFile != audio.absoluteFile || partial(audio).canonicalFile != partial(audio).absoluteFile) return@mapNotNull null
            PendingAudioAsset(audio.path, marker.contentId, "jvm-file-lock-v1")
        }
    }

    override suspend fun resolvePending(location: String, referenced: Boolean): Boolean = withContext(dispatcher) {
        val file = payload(location)
        val marker = readMarker(sidecar(file)) ?: return@withContext false
        check(coordinator.owns(marker.contentId)) { "File recovery requires its content lease" }
        if (referenced) {
            // A missing referenced file is not proof that its partial is garbage.
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return@withContext false
            remove(partial(file))
        } else {
            remove(file)
            remove(partial(file))
        }
        // Last mutation: an interrupted/failed deletion remains replayable.
        remove(sidecar(file))
        true
    }

    override suspend fun confirmLinked(location: String) {
        withContext(dispatcher) {
            check(Files.isRegularFile(payload(location).toPath(), NOFOLLOW_LINKS)) { "Published audio is missing" }
        }
        resolvePending(location, true)
    }

    override suspend fun delete(location: String) {
        if (!resolvePending(location, false)) FileAudioStorage(root, dispatcher).delete(location)
    }
}
