package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.ImmersionStats
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

class SqlDelightContentRepository(
    private val db: AppDatabase
) : BaseSqlDelightRepository(), LocalContentRepository {

    override fun getAllContent(): Flow<List<ImmersionContent>> {
        return db.appDatabaseQueries.selectAllContent().asFlowList { it.toDomain() }
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

    override fun getImmersionStats(): Flow<ImmersionStats> {
        return db.appDatabaseQueries.getImmersionStats().asFlowOne { 
            ImmersionStats(
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
