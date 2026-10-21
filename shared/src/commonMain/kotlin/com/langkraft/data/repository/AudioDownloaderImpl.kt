package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.AudioDownloader
import com.langkraft.io.FileSystem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class AudioDownloadException(message: String) : Exception(message)

class AudioDownloaderImpl(
    private val httpClient: HttpClient,
    private val fileSystem: FileSystem,
    private val db: AppDatabase,
    private val baseUrl: io.ktor.http.Url
) : AudioDownloader {

    override suspend fun downloadAudio(content: ImmersionContent): String {
        require(Regex("[A-Za-z0-9_-]+").matches(content.id)) { "Invalid content ID" }
        val fileName = "${content.id}.mp3"
        val tempFileName = "${content.id}.part"
        val destinationPath = fileSystem.resolve(fileSystem.getAppDataDir(), fileName)
        val tempPath = fileSystem.resolve(fileSystem.getAppDataDir(), tempFileName)
        
        if (fileSystem.exists(destinationPath)) {
            db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.COMPLETED.name, destinationPath, content.id)
            return destinationPath
        }

        db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.DOWNLOADING.name, null, content.id)

        try {
            val downloadUrl = if (content.audioUrl.startsWith("http")) {
                content.audioUrl
            } else {
                URLBuilder(baseUrl).apply {
                    pathSegments = content.audioUrl.split("/").filter { it.isNotEmpty() }
                }.buildString()
            }
            val response = httpClient.get(downloadUrl)
            if (response.status != HttpStatusCode.OK) {
                throw AudioDownloadException("Audio service returned HTTP ${response.status.value}")
            }
            val type = response.contentType()
            if (type != null && !type.contentType.equals("audio", ignoreCase = true) &&
                !type.match(ContentType.Application.OctetStream) &&
                !type.match(ContentType("application", "ogg"))) {
                throw AudioDownloadException("Expected audio but received $type")
            }
            val body = response.body<ByteArray>()
            if (body.isEmpty()) throw AudioDownloadException("Audio response is empty")
            val expectedLength = response.contentLength()
            if (expectedLength != null && expectedLength != body.size.toLong()) {
                throw AudioDownloadException("Audio response is incomplete")
            }
            coroutineContext.ensureActive()
            
            // Write to temporary file first
            fileSystem.writeBytes(tempPath, body)
            
            // Move to final path
            fileSystem.rename(tempPath, destinationPath)
            
            db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.COMPLETED.name, destinationPath, content.id)
            return destinationPath
        } catch (e: CancellationException) {
            runCatching { fileSystem.delete(tempPath) }
            runCatching { db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.IDLE.name, null, content.id) }
            throw e
        } catch (e: Exception) {
            runCatching { fileSystem.delete(tempPath) }
            db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.ERROR.name, null, content.id)
            throw e
        }
    }
}
