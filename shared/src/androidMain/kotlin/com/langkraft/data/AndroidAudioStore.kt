package com.langkraft.data

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidAudioStore(context: Context, private val client: HttpClient) : AudioStore {
    private val directory = File(context.filesDir, "audio").apply { mkdirs() }
    override suspend fun download(contentId: String, audioUrl: String): String = withContext(Dispatchers.IO) {
        require(Regex("[a-zA-Z0-9-]+").matches(contentId)) { "Invalid content ID" }
        val target = File(directory, "$contentId.opus")
        val temporary = File(directory, "$contentId.part")
        try {
            client.prepareGet(audioUrl).execute { response ->
                check(response.status.isSuccess()) { "Audio download failed" }
                temporary.outputStream().use { output -> response.bodyAsChannel().copyTo(output) }
            }
            check(temporary.length() > 0 && temporary.renameTo(target)) { "Cannot save downloaded audio" }
            target.toURI().toString()
        } finally { temporary.delete() }
    }
}