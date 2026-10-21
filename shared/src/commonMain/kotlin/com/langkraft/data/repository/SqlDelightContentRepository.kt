package com.langkraft.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.remote.ContentApi
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.repository.ContentRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SqlDelightContentRepository(
    private val access: DatabaseAccess,
    private val api: ContentApi,
    private val audioStorage: AudioStorage,
    private val nowMillis: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ContentRepository {
    private val database = access.database
    private val queries = database.appDatabaseQueries

    override fun getAllContent(): Flow<List<ImmersionContent>> = queries.selectLibrary()
        .asFlow().map { query -> access.run(dispatcher) { query.awaitAsList() } }.map { rows ->
            rows.groupBy { it.id }.values.map { group ->
                val first = group.first()
                ImmersionContent(first.id, first.title, first.audioUrl, first.localAudioPath,
                    first.sourceUrl, first.durationSeconds, group.mapNotNull { row ->
                        row.subtitleId?.let { id ->
                            SubtitleLine(id, row.id, requireNotNull(row.startMs), requireNotNull(row.endMs),
                                requireNotNull(row.textDe), row.textEn)
                        }
                    })
            }
        }.distinctUntilChanged()

    override suspend fun getContentById(id: String): ImmersionContent? = access.run(dispatcher) {
        database.transactionWithResult {
            val content = queries.selectContentById(id).awaitAsOneOrNull() ?: return@transactionWithResult null
            val subtitles = queries.selectSubtitlesForContent(id).awaitAsList().map {
                SubtitleLine(it.id, it.contentId, it.startMs, it.endMs, it.textDe, it.textEn)
            }
            ImmersionContent(content.id, content.title, content.audioUrl, content.localAudioPath,
                content.sourceUrl, content.durationSeconds, subtitles)
        }
    }

    override suspend fun saveContent(content: ImmersionContent) = access.run(dispatcher) {
        require(content.id.isNotBlank()) { "Content ID must not be blank" }
        val ids = content.subtitles.map { it.id }
        require(ids.toSet().size == ids.size) { "Subtitle IDs must be unique" }
        require(content.subtitles.all { it.contentId == content.id }) { "Subtitles must belong to their content" }
        database.transaction {
            if (ids.isNotEmpty()) {
                require(queries.selectConflictingSubtitles(ids, content.id).awaitAsList().isEmpty()) {
                    "A subtitle ID already belongs to another content item"
                }
            }
            queries.insertContent(id = content.id, title = content.title, audioUrl = content.audioUrl,
                localAudioPath = content.localAudioPath, sourceUrl = content.sourceUrl,
                durationSeconds = content.durationSeconds, createdAt = nowMillis())
            content.subtitles.forEach {
                queries.insertSubtitleLine(id = it.id, contentId = it.contentId, startMs = it.startMs,
                    endMs = it.endMs, textDe = it.textDe, textEn = it.textEn)
            }
            if (ids.isEmpty()) queries.deleteSubtitlesForContent(content.id)
            else queries.deleteObsoleteSubtitles(content.id, ids)
        }
    }

    override suspend fun downloadAudio(content: ImmersionContent): String = api.download(content, audioStorage)

    override suspend fun fetchFromYouTube(url: String): ImmersionContent = api.ingest(url)
}
