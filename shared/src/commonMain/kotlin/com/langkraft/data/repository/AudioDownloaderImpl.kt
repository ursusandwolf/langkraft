package com.langkraft.data.repository

import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.AudioDownloader
import com.langkraft.io.ioDispatcher
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import kotlinx.coroutines.*

class AudioDownloadException(message: String) : Exception(message)

class AudioDownloaderImpl(
    private val httpClient: HttpClient,
    private val storage: AudioStorage,
    private val access: DatabaseAccess,
    private val baseUrl: Url
) : AudioDownloader {
    private suspend fun status(id: String, status: DownloadStatus, path: String? = null) = access.run(ioDispatcher) {
        appDatabaseQueries.updateDownloadStatus(status.name, path, id)
    }

    override suspend fun downloadAudio(content: ImmersionContent): String {
        require(Regex("[A-Za-z0-9_-]+").matches(content.id)) { "Invalid content ID" }
        var location: String? = null
        try {
            status(content.id, DownloadStatus.DOWNLOADING)
            val downloadUrl = URLBuilder(baseUrl).takeFrom(content.audioUrl).buildString()
            location = httpClient.prepareGet(downloadUrl).execute { response ->
                if (response.status != HttpStatusCode.OK) throw AudioDownloadException("Audio service returned HTTP ${response.status.value}")
                val type = response.contentType()
                if (type != null && !type.contentType.equals("audio", ignoreCase = true) &&
                    !type.match(ContentType.Application.OctetStream) && !type.match(ContentType("application", "ogg"))) {
                    throw AudioDownloadException("Expected audio but received $type")
                }
                storage.store(content.id, response.bodyAsChannel(), response.contentLength()).also { location = it }
            }
            currentCoroutineContext().ensureActive()
            status(content.id, DownloadStatus.COMPLETED, location)
            return location
        } catch (exception: Throwable) {
            withContext(NonCancellable) {
                location?.let { runCatching { storage.delete(it) } }
                runCatching { status(content.id, if (exception is CancellationException) DownloadStatus.IDLE else DownloadStatus.ERROR) }
            }
            throw exception
        }
    }
}
