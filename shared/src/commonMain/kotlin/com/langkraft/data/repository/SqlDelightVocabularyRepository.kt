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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightVocabularyRepository(
    private val db: AppDatabase,
    private val httpClient: HttpClient,
    private val baseUrl: String
) : VocabularyRepository {

    private val json = Json { ignoreUnknownKeys = true }

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
                tags = json.encodeToString(word.tags),
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
        if (pendingChanges.isEmpty()) {
            // Even if no local changes, we should fetch server changes
            return fetchServerChanges(lastSyncTimestamp, emptyList())
        }

        val upsertIds = pendingChanges.filter { it.changeType == "UPSERT" }.map { it.wordId }
        val wordsById = if (upsertIds.isNotEmpty()) {
            db.appDatabaseQueries.selectWordsByIds(upsertIds).executeAsList()
                .associate { it.id to it.toDomain() }
        } else {
            emptyMap()
        }

        val changedWords = pendingChanges.map { change ->
            if (change.changeType == "DELETE") {
                // Represent deleted word for sync. 
                VocabularyWord(
                    id = change.wordId, word = "", lemma = "", translation = "", contextSentence = "",
                    contentId = "", subtitleLineId = "", addedAt = 0, nextReviewMs = 0,
                    intervalDays = 0, easeFactor = 0.0, status = WordStatus.NEW, lapseCount = -1, tags = emptyList(), lastUpdated = change.timestamp
                )
            } else {
                wordsById[change.wordId]
            }
        }.filterNotNull()

        return fetchServerChanges(lastSyncTimestamp, changedWords)
    }

    private suspend fun fetchServerChanges(lastSyncTimestamp: Long, changedWords: List<VocabularyWord>): Long {
        return try {
            val response: SyncResponse = httpClient.post("$baseUrl/api/sync") {
                contentType(ContentType.Application.Json)
                setBody(SyncRequest(lastSyncTimestamp, changedWords))
            }.body()

            db.appDatabaseQueries.transaction {
                response.serverChanges.forEach { word ->
                    // If lapseCount is -1, it's a deletion marker
                    if (word.lapseCount == -1) {
                        db.appDatabaseQueries.deleteWord(word.id)
                    } else {
                        db.appDatabaseQueries.upsertWord(
                            word.id, word.word, word.lemma, word.translation, word.contextSentence,
                            word.contentId, word.subtitleLineId, word.addedAt, word.nextReviewMs,
                            word.intervalDays.toLong(), word.easeFactor, word.status.name,
                            word.lapseCount.toLong(), json.encodeToString(word.tags), word.lastUpdated
                        )
                    }
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
            tags = try { json.decodeFromString<List<String>>(tags) } catch (e: Exception) { emptyList() },
            lastUpdated = lastUpdated
        )
    }
}
