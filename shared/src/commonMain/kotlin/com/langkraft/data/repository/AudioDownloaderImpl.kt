package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.repository.AudioDownloader
import com.langkraft.io.FileSystem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class AudioDownloaderImpl(
    private val httpClient: HttpClient,
    private val fileSystem: FileSystem,
    private val db: AppDatabase,
    private val baseUrl: String
) : AudioDownloader {

    override suspend fun downloadAudio(content: ImmersionContent): String {
        val fileName = "${content.id}.opus"
        val tempFileName = "${content.id}.part"
        val destinationPath = fileSystem.resolve(fileSystem.getAppDataDir(), fileName)
        val tempPath = fileSystem.resolve(fileSystem.getAppDataDir(), tempFileName)
        
        if (fileSystem.exists(destinationPath)) {
            db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.COMPLETED.name, destinationPath, content.id)
            return destinationPath
        }

        db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.DOWNLOADING.name, null, content.id)

        try {
            val downloadUrl = if (content.audioUrl.startsWith("http")) content.audioUrl else "$baseUrl${content.audioUrl}"
            val response = httpClient.get(downloadUrl)
            val body = response.body<ByteArray>()
            
            // Write to temporary file first
            fileSystem.writeBytes(tempPath, body)
            
            // Move to final path
            fileSystem.rename(tempPath, destinationPath)
            
            db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.COMPLETED.name, destinationPath, content.id)
            return destinationPath
        } catch (e: Exception) {
            db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.ERROR.name, null, content.id)
            throw e
        }
    }
}
