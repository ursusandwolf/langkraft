@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.langkraft.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import com.langkraft.db.Content
import com.langkraft.domain.model.*
import com.langkraft.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class SqlContentRepository(
    private val local: LocalDatabase,
    private val remote: ContentRemote,
    private val audioStore: AudioStore,
) : ContentRepository {
    private val queries = local.database.appDatabaseQueries

    override fun getAllContent(): Flow<List<ImmersionContent>> = queries.selectAllContent().asFlow().map { query ->
        local.mutex.withLock { query.awaitAsList().map { it.toModel() } }
    }
    override suspend fun getContentById(id: String): ImmersionContent? =
        local.mutex.withLock { queries.selectContent(id).awaitAsOneOrNull()?.toModel() }

    private suspend fun Content.toModel() = ImmersionContent(
        id, title, audioUrl, localAudioPath, sourceUrl, durationSeconds,
        queries.selectSubtitlesForContent(id).awaitAsList().map {
            SubtitleLine(it.id, it.contentId, it.startMs, it.endMs, it.textDe, it.textEn)
        },
    )

    override suspend fun saveContent(content: ImmersionContent) {
        require(content.subtitles.all { it.contentId == content.id && it.startMs >= 0 && it.endMs > it.startMs }) { "Invalid subtitle content or time range" }
        require(content.subtitles.map { it.id }.distinct().size == content.subtitles.size) { "Duplicate subtitle ID" }
        local.mutex.withLock {
            local.database.transaction {
                queries.insertContent(content.id, content.title, content.audioUrl, content.localAudioPath,
                    content.sourceUrl, content.durationSeconds, Clock.System.now().toEpochMilliseconds())
                queries.updateContent(content.title, content.audioUrl, content.localAudioPath, content.sourceUrl, content.durationSeconds, content.id)
                val currentIds = content.subtitles.map { it.id }.toSet()
                queries.selectSubtitlesForContent(content.id).awaitAsList().filter { it.id !in currentIds }.forEach {
                    queries.deleteSubtitleLine(it.id)
                }
                content.subtitles.forEach {
                    queries.insertSubtitleLine(it.id, it.contentId, it.startMs, it.endMs, it.textDe, it.textEn)
                    queries.updateSubtitleLine(it.startMs, it.endMs, it.textDe, it.textEn, it.id, content.id)
                }
            }
        }
    }
    override suspend fun fetchFromYouTube(url: String) = remote.ingest(url)
    override suspend fun downloadAudio(content: ImmersionContent) = audioStore.download(content.id, content.audioUrl)
}

class SqlVocabularyRepository(private val local: LocalDatabase) : VocabularyRepository {
    private val queries = local.database.appDatabaseQueries
    override fun getWordsToReview(): Flow<List<VocabularyWord>> = queries.selectVocabularyToReview(Clock.System.now().toEpochMilliseconds()).asFlow().map { query ->
        local.mutex.withLock {
            query.awaitAsList().map { VocabularyWord(it.id, it.word, it.lemma, it.translation, it.contextSentence, it.contentId, it.subtitleLineId, WordStatus.valueOf(it.status)) }
        }
    }
    override suspend fun saveWord(word: VocabularyWord) {
        local.mutex.withLock {
            local.database.transaction {
                queries.insertWord(word.id, word.word, word.lemma, word.translation, word.contextSentence,
                    word.contentId, word.subtitleLineId, Clock.System.now().toEpochMilliseconds(), word.status.name)
                queries.updateWord(word.word, word.lemma, word.translation, word.contextSentence,
                    word.contentId, word.subtitleLineId, word.status.name, word.id)
            }
        }
    }
    override suspend fun deleteWord(id: String) { local.mutex.withLock { queries.deleteWord(id) } }
}