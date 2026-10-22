package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    access: com.langkraft.data.database.DatabaseAccess
) : BaseSqlDelightRepository(access), LocalContentRepository {
    private val db = access.database

    override fun getAllContent(): Flow<List<ImmersionContent>> {
        return db.appDatabaseQueries.selectAllContent().asFlowList { it.toDomain() }
    }

    override suspend fun getContentById(id: String): ImmersionContent? = access.run(dispatcher) {
        val content = db.appDatabaseQueries.selectContentById(id).awaitAsOneOrNull() ?: return@run null
        val subtitles = db.appDatabaseQueries.selectSubtitlesForContent(id).awaitAsList().map { it.toDomain() }
        content.toDomain().copy(subtitles = subtitles)
    }

    override suspend fun saveContent(content: ImmersionContent) = access.run(dispatcher) {
        val ids = content.subtitles.map { it.id }
        require(ids.toSet().size == ids.size && content.subtitles.all { it.contentId == content.id }) { "Invalid subtitle ownership or duplicate ID" }
        db.transaction {
            val existing = db.appDatabaseQueries.selectContentById(content.id).awaitAsOneOrNull()
            val keepOffline = existing?.downloadStatus == DownloadStatus.COMPLETED.name && content.downloadStatus != DownloadStatus.COMPLETED
            if (ids.isNotEmpty()) require(db.appDatabaseQueries.selectConflictingSubtitles(ids, content.id).awaitAsList().isEmpty()) { "Subtitle belongs to another content" }
            db.appDatabaseQueries.insertContent(
                id = content.id,
                title = content.title,
                audioUrl = content.audioUrl,
                localAudioPath = if (keepOffline) existing?.localAudioPath else content.localAudioPath,
                sourceUrl = content.sourceUrl,
                durationSeconds = content.durationSeconds,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                downloadStatus = if (keepOffline) DownloadStatus.COMPLETED.name else content.downloadStatus.name,
                waveform = if (content.waveform.isEmpty() && existing != null) existing.waveform else Json.encodeToString(content.waveform)
            )
            
            content.subtitles.forEachIndexed { index, line ->
                db.appDatabaseQueries.insertSubtitleLine(
                    id = line.id,
                    contentId = content.id,
                    startMs = line.startMs,
                    endMs = line.endMs,
                    originalText = line.originalText,
                    translationText = line.translationText,
                    ordinal = index.toLong()
                )
            }
            if (ids.isEmpty()) db.appDatabaseQueries.deleteSubtitlesByContentId(content.id)
            else db.appDatabaseQueries.deleteObsoleteSubtitles(content.id, ids)
            db.appDatabaseQueries.selectSourceReferenceMetadata().awaitAsList().forEach { metadata ->
                val refs = runCatching { Json.decodeFromString<List<String?>>(metadata.value_) }.getOrNull()
                if (refs?.getOrNull(0) == content.id) {
                    db.appDatabaseQueries.updateWordReferences(content.id,
                        refs.getOrNull(1)?.takeIf { it in ids }, metadata.key.removePrefix("source_refs:"))
                }
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
            waveform = runCatching { Json.decodeFromString<List<Float>>(waveform) }.getOrDefault(emptyList()),
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
