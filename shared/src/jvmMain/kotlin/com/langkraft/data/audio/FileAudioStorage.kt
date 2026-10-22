package com.langkraft.data.audio

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Persist platform recovery metadata before any payload bytes are created. */
    private val beforeWrite: suspend (contentId: String, location: String) -> Unit = { _, _ -> }
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
    ): String {
        var published: File? = null
        var temporary: File? = null
        try {
            return withContext(dispatcher) {
                require(Regex("[A-Za-z0-9_-]{1,128}").matches(contentId)) { "Invalid content ID" }
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create audio storage" }
                val file = File(directory, "$contentId-${UUID.randomUUID()}.mp3").also { published = it }
                val partial = File(directory, "${file.name}.part").also { temporary = it }
                beforeWrite(contentId, file.absolutePath)
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
            }
        } catch (exception: Throwable) {
            // This catch also covers prompt cancellation while withContext hands
            // the location back to its caller, after the file has been renamed.
            withContext(NonCancellable + dispatcher) {
                for (file in listOfNotNull(temporary, published)) {
                    try { check(!file.exists() || file.delete()) { "Cannot remove incomplete audio" } }
                    catch (cleanup: Throwable) { exception.addSuppressed(cleanup) }
                }
            }
            throw exception
        }
    }
}
