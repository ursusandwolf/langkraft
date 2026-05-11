package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.repository.ContentRepository
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
) : ContentRepository {

    override fun getAllContent(): Flow<List<ImmersionContent>> {
        return db.appDatabaseQueries.selectAllContent()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { it.toDomain() }
            }
    }

    override suspend fun getContentById(id: String): ImmersionContent? {
        val content = db.appDatabaseQueries.selectAllContent().executeAsList().find { it.id == id } ?: return null
        val subtitles = db.appDatabaseQueries.selectSubtitlesForContent(id).executeAsList().map { it.toDomain() }
        return content.toDomain().copy(subtitles = subtitles)
    }

    override suspend fun saveContent(content: ImmersionContent) {
        db.transaction {
            db.appDatabaseQueries.insertContent(
                id = content.id,
                title = content.title,
                audioUrl = content.audioUrl,
                localAudioPath = content.localAudioPath,
                sourceUrl = content.sourceUrl,
                durationSeconds = content.durationSeconds,
                createdAt = Clock.System.now().toEpochMilliseconds()
            )
            
            content.subtitles.forEach { line ->
                db.appDatabaseQueries.insertSubtitleLine(
                    id = line.id,
                    contentId = content.id,
                    startMs = line.startMs,
                    endMs = line.endMs,
                    textDe = line.textDe,
                    textEn = line.textEn
                )
            }
        }
    }

    override suspend fun downloadAudio(content: ImmersionContent): String {
        val fileName = "${content.id}.opus"
        val destinationPath = "${fileSystem.getAppDataDir()}/$fileName"
        
        if (fileSystem.exists(destinationPath)) return destinationPath

        val response = httpClient.get(content.audioUrl)
        val body = response.body<ByteArray>()
        
        fileSystem.writeBytes(destinationPath, body)
        
        db.appDatabaseQueries.updateLocalAudioPath(destinationPath, content.id)
        
        return destinationPath
    }

    override suspend fun fetchFromYouTube(url: String): ImmersionContent {
        return ImmersionContent(
            id = "temp", 
            title = "Loading...", 
            audioUrl = "", 
            sourceUrl = url, 
            localAudioPath = null,
            durationSeconds = 0,
            subtitles = emptyList(),
            waveform = emptyList()
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
            waveform = emptyList() // Will be loaded separately or generated
        )
    }

    private fun com.langkraft.db.SubtitleLine.toDomain(): SubtitleLine {
        return SubtitleLine(
            id = id,
            contentId = contentId,
            startMs = startMs,
            endMs = endMs,
            textDe = textDe,
            textEn = textEn
        )
    }
}
