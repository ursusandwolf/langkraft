package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.CancellationException
import com.langkraft.domain.model.*
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.io.Logger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightVocabularyRepository(
    access: com.langkraft.data.database.DatabaseAccess,
    private val httpClient: HttpClient,
    private val baseUrl: io.ktor.http.Url
) : BaseSqlDelightRepository(access), VocabularyRepository {

    private val db = access.database
    private val json = Json { ignoreUnknownKeys = true }

    override fun getWordsToReview(): Flow<List<VocabularyWord>> {
        val now = Clock.System.now().toEpochMilliseconds()
        return db.appDatabaseQueries.selectVocabularyToReview(now).asFlowList { it.toDomain() }
    }

    override fun getReviewCount(): Flow<Int> {
        val now = Clock.System.now().toEpochMilliseconds()
        return db.appDatabaseQueries.countVocabularyToReview(now).asFlowOne { it.toInt() }
    }

    override suspend fun saveWord(word: VocabularyWord) = access.run(dispatcher) {
        db.appDatabaseQueries.transaction {
            val revision = maxOf(Clock.System.now().toEpochMilliseconds(),
                (db.appDatabaseQueries.selectPendingChange(word.id).awaitAsOneOrNull()?.timestamp ?: 0L) + 1,
                (db.appDatabaseQueries.selectWordById(word.id).awaitAsOneOrNull()?.lastUpdated ?: 0L) + 1)
            db.appDatabaseQueries.upsertWord(
                id = word.id,
                word = word.word,
                lemma = word.lemma,
                translation = word.translation,
                contextSentence = word.contextSentence,
                contentId = word.contentId,
                subtitleLineId = word.subtitleLineId,
                addedAt = word.addedAt,
                status = word.status.name,
                nextReviewAt = word.nextReviewMs,
                intervalDays = word.intervalDays.toLong(),
                easeFactor = word.easeFactor,
                lapseCount = word.lapseCount.toLong(),
                tags = json.encodeToString(word.tags),
                lastUpdated = revision
            )
            db.appDatabaseQueries.insertPendingChange(
                wordId = word.id,
                changeType = "UPSERT",
                timestamp = revision
            )
        }
    }

    override suspend fun deleteWord(id: String) = access.run(dispatcher) {
        db.appDatabaseQueries.transaction {
            val revision = maxOf(Clock.System.now().toEpochMilliseconds(),
                (db.appDatabaseQueries.selectPendingChange(id).awaitAsOneOrNull()?.timestamp ?: 0L) + 1,
                (db.appDatabaseQueries.selectWordById(id).awaitAsOneOrNull()?.lastUpdated ?: 0L) + 1)
            db.appDatabaseQueries.deleteWord(id)
            db.appDatabaseQueries.insertPendingChange(
                wordId = id,
                changeType = "DELETE",
                timestamp = revision
            )
        }
    }

    override suspend fun sync(lastSyncTimestamp: Long): Long {
        val pendingChanges = access.run(dispatcher) { db.appDatabaseQueries.getAllPendingChanges().awaitAsList() }
        val processedIds = pendingChanges

        if (pendingChanges.isEmpty()) {
            return fetchServerChanges(lastSyncTimestamp, emptyList(), emptyList())
        }

        val upsertIds = pendingChanges.filter { it.changeType == "UPSERT" }.map { it.wordId }
        val wordsById = if (upsertIds.isNotEmpty()) {
            access.run(dispatcher) { db.appDatabaseQueries.selectWordsByIds(upsertIds).awaitAsList() }
                .associate { it.id to it.toDomain() }
        } else {
            emptyMap()
        }

        val clientEntries = pendingChanges.mapNotNull { change ->
            if (change.changeType == "DELETE") {
                SyncEntry(
                    word = VocabularyWord(
                        id = change.wordId, word = "", lemma = "", translation = "", contextSentence = "",
                        contentId = "", subtitleLineId = "", addedAt = 0, nextReviewMs = 0,
                        intervalDays = 0, easeFactor = 0.0, status = WordStatus.NEW, lapseCount = 0, tags = emptyList(), lastUpdated = change.timestamp
                    ),
                    changeType = "DELETE"
                )
            } else {
                wordsById[change.wordId]?.let { word ->
                    val references = access.run(dispatcher) {
                        db.appDatabaseQueries.getSyncMetadata("source_refs:${word.id}").awaitAsOneOrNull()
                            ?.let { json.decodeFromString<List<String?>>(it) }
                    }
                    SyncEntry(word.copy(contentId=word.contentId ?: references?.getOrNull(0),
                        subtitleLineId=word.subtitleLineId ?: references?.getOrNull(1)), "UPSERT")
                }
            }
        }

        return fetchServerChanges(lastSyncTimestamp, clientEntries, processedIds)
    }

    private suspend fun fetchServerChanges(
        lastSyncTimestamp: Long, 
        clientChanges: List<SyncEntry>,
        processedIds: List<com.langkraft.db.PendingSyncChange>
    ): Long {
        return try {
            val response: SyncResponse = httpClient.post(baseUrl) {
                url { appendEncodedPathSegments("api", "sync") }
                contentType(ContentType.Application.Json)
                setBody(SyncRequest(lastSyncTimestamp, clientChanges))
            }.body()

            access.run(dispatcher) { db.transaction {
                response.serverChanges.forEach { entry ->
                    val pending = db.appDatabaseQueries.selectPendingChange(entry.word.id).awaitAsOneOrNull()
                    val sent = processedIds.firstOrNull { it.wordId == entry.word.id }
                    if (pending != null && pending != sent) return@forEach
                    if (entry.changeType == "DELETE") {
                        db.appDatabaseQueries.deleteWord(entry.word.id)
                        db.appDatabaseQueries.deleteSyncMetadata("source_refs:${entry.word.id}")
                    } else {
                        val word = entry.word
                        // A second client may not have imported the source video. Preserve remote
                        // references separately while keeping local foreign keys valid.
                        db.appDatabaseQueries.setSyncMetadata("source_refs:${word.id}",
                            json.encodeToString(listOf(word.contentId, word.subtitleLineId)))
                        val localContent = word.contentId?.takeIf {
                            db.appDatabaseQueries.selectContentById(it).awaitAsOneOrNull() != null
                        }
                        val localSubtitle = word.subtitleLineId?.takeIf {
                            db.appDatabaseQueries.selectSubtitleById(it).awaitAsOneOrNull()?.contentId == localContent && localContent != null
                        }
                        db.appDatabaseQueries.upsertWord(
                            id=word.id, word=word.word, lemma=word.lemma, translation=word.translation, contextSentence=word.contextSentence,
                            contentId=localContent, subtitleLineId=localSubtitle, addedAt=word.addedAt, nextReviewAt=word.nextReviewMs,
                            intervalDays=word.intervalDays.toLong(), easeFactor=word.easeFactor, status=word.status.name,
                            lapseCount=word.lapseCount.toLong(), tags=json.encodeToString(word.tags), lastUpdated=word.lastUpdated
                        )
                    }
                }
                if (processedIds.isNotEmpty()) {
                    processedIds.forEach { change -> db.appDatabaseQueries.deleteAcknowledgedChange(change.wordId, change.timestamp, change.changeType) }
                }
            } }
            response.serverTimestamp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("Sync failed", e)
            throw e
        }
    }

    override fun getWordCountsByStatus(): Flow<Map<WordStatus, Long>> {
        return db.appDatabaseQueries.countWordsByStatus().asFlowList { 
            WordStatus.valueOf(it.status) to it.count 
        }.map { it.toMap() }
    }

    override fun getWordsAddedSince(timestamp: Long): Flow<Long> {
        return db.appDatabaseQueries.getWordsAddedRecently(timestamp).asFlowOne { it }
    }

    override suspend fun getSyncMetadata(key: String): String? {
        return access.run(dispatcher) { db.appDatabaseQueries.getSyncMetadata(key).awaitAsOneOrNull() }
    }

    override suspend fun setSyncMetadata(key: String, value: String) {
        access.run(dispatcher) { db.appDatabaseQueries.setSyncMetadata(key, value) }
    }

    private fun com.langkraft.db.Vocabulary.toDomain(): VocabularyWord {
        return VocabularyWord(
            id = id,
            word = word,
            lemma = lemma,
            translation = translation,
            contextSentence = contextSentence ?: "",
            contentId = contentId,
            subtitleLineId = subtitleLineId,
            addedAt = addedAt,
            nextReviewMs = nextReviewAt ?: 0L,
            intervalDays = intervalDays.toInt(),
            easeFactor = easeFactor,
            status = WordStatus.valueOf(status),
            lapseCount = lapseCount.toInt(),
            tags = try { json.decodeFromString<List<String>>(tags) } catch (e: Exception) { emptyList() },
            lastUpdated = lastUpdated
        )
    }
}
