package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.RemoteContentSource
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

import com.langkraft.io.FileSystem

class SqlDelightContentRepository(
    private val db: AppDatabase,
    private val httpClient: HttpClient,
    private val fileSystem: FileSystem
) : LocalContentRepository, RemoteContentSource, com.langkraft.domain.repository.AudioDownloader {

    override fun getAllContent(): Flow<List<ImmersionContent>> {
        return db.appDatabaseQueries.selectAllContent()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { it.toDomain() }
            }
    }

    override suspend fun getContentById(id: String): ImmersionContent? {
        val content = db.appDatabaseQueries.selectContentById(id).executeAsOneOrNull() ?: return null
        val subtitles = db.appDatabaseQueries.selectSubtitlesForContent(id).executeAsList().map { it.toDomain() }
        return content.toDomain().copy(subtitles = subtitles)
    }

    override suspend fun saveContent(content: ImmersionContent) {
        db.transaction {
            db.appDatabaseQueries.deleteSubtitlesByContentId(content.id)
            db.appDatabaseQueries.insertContent(
                id = content.id,
                title = content.title,
                audioUrl = content.audioUrl,
                localAudioPath = content.localAudioPath,
                sourceUrl = content.sourceUrl,
                durationSeconds = content.durationSeconds,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                downloadStatus = content.downloadStatus.name
            )
            
            content.subtitles.forEach { line ->
                db.appDatabaseQueries.insertSubtitleLine(
                    id = line.id,
                    contentId = content.id,
                    startMs = line.startMs,
                    endMs = line.endMs,
                    originalText = line.originalText,
                    translationText = line.translationText
                )
            }
        }
    }

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
            val response = httpClient.get(content.audioUrl)
            val body = response.body<ByteArray>()
            
            // Write to temporary file first
            fileSystem.writeBytes(tempPath, body)
            
            // "Rename" by deleting existing (if any) and writing to final path
            // Note: Our FileSystem abstraction is simple, so we just write the bytes to the new path
            // In a real KMP app, FileSystem would have a rename() method.
            fileSystem.writeBytes(destinationPath, body)
            fileSystem.delete(tempPath)
            
            db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.COMPLETED.name, destinationPath, content.id)
            return destinationPath
        } catch (e: Exception) {
            db.appDatabaseQueries.updateDownloadStatus(DownloadStatus.ERROR.name, null, content.id)
            throw e
        }
    }

    override suspend fun fetchFromYouTube(url: String): ImmersionContent {
        // Implementation for backend call would go here
        return ImmersionContent(
            id = "temp", 
            title = "Loading...", 
            audioUrl = "", 
            sourceUrl = url, 
            localAudioPath = null,
            durationSeconds = 0,
            subtitles = emptyList(),
            waveform = emptyList(),
            downloadStatus = DownloadStatus.IDLE
        )
    }

    override fun getImmersionStats(): Flow<com.langkraft.domain.repository.ImmersionStats> {
        return db.appDatabaseQueries.getImmersionStats()
            .asFlow()
            .mapToOne(Dispatchers.Default)
            .map { 
                com.langkraft.domain.repository.ImmersionStats(
                    totalContent = it.totalContent,
                    totalDurationSeconds = it.totalDurationSeconds ?: 0L
                )
            }
    }

    private fun com.langkraft.db.Content.toDomain(): ImmersionContent {
        return ImmersionContent(
            id = id,
            title = title,
            audioUrl = audioUrl,
            localAudioPath = localAudioPath,
            sourceUrl = sourceUrl,
            durationSeconds = durationSeconds,
            subtitles = emptyList(),
            waveform = emptyList(),
            downloadStatus = DownloadStatus.valueOf(downloadStatus)
        )
    }

    private fun com.langkraft.db.SubtitleLine.toDomain(): SubtitleLine {
        return SubtitleLine(
            id = id,
            contentId = contentId,
            startMs = startMs,
            endMs = endMs,
            originalText = originalText,
            translationText = translationText
        )
    }
}
