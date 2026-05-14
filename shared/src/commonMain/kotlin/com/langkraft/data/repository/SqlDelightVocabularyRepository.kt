package com.langkraft.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.SyncRequest
import com.langkraft.domain.model.SyncResponse
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import com.langkraft.domain.repository.VocabularyRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

class SqlDelightVocabularyRepository(
    private val db: AppDatabase,
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://api.langkraft.com"
) : VocabularyRepository {


    override fun getWordsToReview(): Flow<List<VocabularyWord>> {
        val now = Clock.System.now().toEpochMilliseconds()
        return db.appDatabaseQueries.selectVocabularyToReview(now)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { it.toDomain() }
            }
    }

    override fun getReviewCount(): Flow<Int> {
        val now = Clock.System.now().toEpochMilliseconds()
        return db.appDatabaseQueries.countVocabularyToReview(now)
            .asFlow()
            .mapToOne(Dispatchers.Default)
            .map { it.toInt() }
    }

    override suspend fun saveWord(word: VocabularyWord) {
        db.appDatabaseQueries.transaction {
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
                tags = word.tags.joinToString(","),
                lastUpdated = Clock.System.now().toEpochMilliseconds()
            )
            db.appDatabaseQueries.insertPendingChange(
                wordId = word.id,
                changeType = "UPSERT",
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
        }
    }

    override suspend fun deleteWord(id: String) {
        db.appDatabaseQueries.transaction {
            db.appDatabaseQueries.deleteWord(id)
            db.appDatabaseQueries.insertPendingChange(
                wordId = id,
                changeType = "DELETE",
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
        }
    }

    override suspend fun sync(lastSyncTimestamp: Long): Long {
        val pendingChanges = db.appDatabaseQueries.getAllPendingChanges().executeAsList()
        val changedWords = pendingChanges.map { change ->
            val wordEntity = db.appDatabaseQueries.selectWordById(change.wordId).executeAsOneOrNull()
            wordEntity?.toDomain() ?: VocabularyWord(
                id = change.wordId, word = "", lemma = "", translation = "", contextSentence = "",
                contentId = "", subtitleLineId = "", addedAt = 0, nextReviewMs = 0,
                intervalDays = 0, easeFactor = 0.0, status = WordStatus.NEW, lapseCount = 0, tags = emptyList(), lastUpdated = 0
            )
        }

        return try {
            val response: SyncResponse = httpClient.post("$baseUrl/api/sync") {
                contentType(ContentType.Application.Json)
                setBody(SyncRequest(lastSyncTimestamp, changedWords))
            }.body()

            db.appDatabaseQueries.transaction {
                response.serverChanges.forEach { word ->
                    db.appDatabaseQueries.upsertWord(
                        word.id, word.word, word.lemma, word.translation, word.contextSentence,
                        word.contentId, word.subtitleLineId, word.addedAt, word.nextReviewMs,
                        word.intervalDays.toLong(), word.easeFactor, word.status.name,
                        word.lapseCount.toLong(), word.tags.joinToString(","), word.lastUpdated
                    )
                }
                db.appDatabaseQueries.clearAllPendingChanges()
            }
            response.serverTimestamp
        } catch (e: Exception) {
            println("Sync failed: ${e.message}")
            lastSyncTimestamp
        }
    }

    override fun getWordCountsByStatus(): Flow<Map<WordStatus, Long>> {
        return db.appDatabaseQueries.countWordsByStatus()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.associate { WordStatus.valueOf(it.status) to it.count }
            }
    }

    override fun getWordsAddedSince(timestamp: Long): Flow<Long> {
        return db.appDatabaseQueries.getWordsAddedRecently(timestamp)
            .asFlow()
            .mapToOne(Dispatchers.Default)
    }

    override suspend fun getSyncMetadata(key: String): String? {
        return db.appDatabaseQueries.getSyncMetadata(key).executeAsOneOrNull()
    }

    override suspend fun setSyncMetadata(key: String, value: String) {
        db.appDatabaseQueries.setSyncMetadata(key, value)
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
            tags = if (tags.isEmpty()) emptyList() else tags.split(","),
            lastUpdated = lastUpdated
        )
    }
}
