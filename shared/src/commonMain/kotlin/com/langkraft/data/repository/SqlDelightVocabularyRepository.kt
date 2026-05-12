package com.langkraft.data.repository

import com.langkraft.db.AppDatabase
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import com.langkraft.domain.repository.VocabularyRepository
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

class SqlDelightVocabularyRepository(
    private val db: AppDatabase
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
        // Collect pending changes
        val pendingChanges = db.appDatabaseQueries.getAllPendingChanges().executeAsList()
        
        // TODO: In Phase 7, send these pendingChanges to the backend via HttpClient:
        // val request = SyncRequest(lastSyncTimestamp, clientChanges)
        // val response = httpClient.post("/api/sync") { setBody(request) }.body<SyncResponse>()
        // Then apply response.serverChanges to the local DB.
        
        // For now, to enable offline functionality without crashing:
        db.appDatabaseQueries.clearAllPendingChanges()
        return Clock.System.now().toEpochMilliseconds()
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
