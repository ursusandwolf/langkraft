package com.langkraft.data.repository

import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.AudioDownloader
import com.langkraft.io.ioDispatcher
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
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
    override suspend fun downloadAudio(content: ImmersionContent): String {
        require(Regex("[A-Za-z0-9_-]+").matches(content.id)) { "Invalid content ID" }
        var location: String? = null
        var previous: com.langkraft.db.Content? = null
        var linked = false
        try {
            // Finish the short state transition even if cancellation arrives
            // while returning from the database dispatcher.
            withContext(NonCancellable) {
                access.run(ioDispatcher) {
                    val row = appDatabaseQueries.selectContentById(content.id).awaitAsOneOrNull()
                        ?: throw AudioDownloadException("Content no longer exists")
                    if (row.downloadStatus == DownloadStatus.DOWNLOADING.name) {
                        throw AudioDownloadException("Audio download is already running")
                    }
                    previous = row
                    appDatabaseQueries.updateDownloadStatus(DownloadStatus.DOWNLOADING.name, row.localAudioPath, content.id)
                }
            }
            currentCoroutineContext().ensureActive()
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
            withContext(NonCancellable) {
                access.run(ioDispatcher) {
                    val row = appDatabaseQueries.selectContentById(content.id).awaitAsOneOrNull()
                        ?: throw AudioDownloadException("Content was deleted during download")
                    if (row.localAudioPath != previous?.localAudioPath || row.downloadStatus != DownloadStatus.DOWNLOADING.name) {
                        throw AudioDownloadException("Offline audio changed during download")
                    }
                    appDatabaseQueries.updateDownloadStatus(DownloadStatus.COMPLETED.name, location, content.id)
                    linked = true
                }
            }
            currentCoroutineContext().ensureActive()
            return requireNotNull(location)
        } catch (exception: Throwable) {
            if (!linked) withContext(NonCancellable) {
                try {
                    access.run(ioDispatcher) {
                        val row = appDatabaseQueries.selectContentById(content.id).awaitAsOneOrNull()
                        location?.let { published ->
                            // A thrown return/notification does not prove that SQL rolled back.
                            // If the outcome cannot be read, keep the asset for later recovery.
                            if (appDatabaseQueries.selectContentReferencingAudio(published).awaitAsOneOrNull() == null) {
                                try { storage.delete(published) }
                                catch (cleanup: Throwable) { exception.addSuppressed(cleanup) }
                            }
                        }
                        val original = previous
                        if (original != null && row?.downloadStatus == DownloadStatus.DOWNLOADING.name &&
                            row.localAudioPath == original.localAudioPath) {
                            val restoredStatus = if (original.localAudioPath != null) original.downloadStatus
                                else if (exception is CancellationException) DownloadStatus.IDLE.name else DownloadStatus.ERROR.name
                            appDatabaseQueries.updateDownloadStatus(restoredStatus, original.localAudioPath, content.id)
                        }
                    }
                } catch (cleanup: Throwable) { exception.addSuppressed(cleanup) }
            }
            throw exception
        }
    }
}
