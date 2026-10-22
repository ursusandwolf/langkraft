package com.langkraft.data.audio

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Used by Android and JVM; incomplete downloads are never exposed as playable files. */
class FileAudioStorage(
    private val directory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AudioStorage {
    override suspend fun delete(location: String) = withContext(dispatcher) {
        val file = File(location).canonicalFile
        require(file.parentFile == directory.canonicalFile) { "Audio location is outside storage" }
        check(!file.exists() || file.delete()) { "Cannot delete audio" }
    }
    override suspend fun store(
        contentId: String,
        source: ByteReadChannel,
        expectedBytes: Long?
    ): String = withContext(dispatcher) {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(contentId)) { "Invalid content ID" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create audio storage" }
        val file = File(directory, "$contentId-${UUID.randomUUID()}.mp3")
        val partial = File(directory, "${file.name}.part")
        try {
            var total = 0L
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.readAvailable(buffer, 0, buffer.size)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                    total += count
                }
                if (total == 0L) throw IOException("Audio response was empty")
                if (expectedBytes != null && expectedBytes != total) {
                    throw IOException("Audio response ended before the complete file was received")
                }
                output.fd.sync()
            }
            coroutineContext.ensureActive()
            if (!partial.renameTo(file)) throw IOException("Cannot publish downloaded audio")
            coroutineContext.ensureActive()
            file.absolutePath
        } catch (exception: Throwable) {
            partial.delete()
            file.delete()
            throw exception
        }
    }
}
