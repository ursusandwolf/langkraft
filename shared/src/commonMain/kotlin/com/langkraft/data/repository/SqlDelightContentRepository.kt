package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.repository.ContentRepository
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

class SqlDelightContentRepository(
    private val db: AppDatabase,
    private val httpClient: HttpClient
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
        // Mock implementation of audio download
        return "local/path/to/${content.id}.opus"
    }

    override suspend fun fetchFromYouTube(url: String): ImmersionContent {
        // This would call the Ktor backend /api/ingest
        // For now, it's a mock or should use httpClient
        return ImmersionContent(id="temp", title="Loading...", audioUrl="", sourceUrl=url, subtitles=emptyList())
    }

    private fun com.langkraft.db.Content.toDomain(): ImmersionContent {
        return ImmersionContent(
            id = id,
            title = title,
            audioUrl = audioUrl,
            localAudioPath = localAudioPath,
            sourceUrl = sourceUrl,
            durationSeconds = durationSeconds,
            subtitles = emptyList() // Will be loaded separately
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
