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

    override suspend fun saveWord(word: VocabularyWord) {
        db.appDatabaseQueries.upsertWord(
            id = word.id,
            word = word.word,
            lemma = word.lemma,
            translation = word.translation,
            contextSentence = word.contextSentence,
            contentId = word.contentId,
            subtitleLineId = word.subtitleLineId,
            addedAt = word.addedAt,
            status = word.status.name
        )
    }

    override suspend fun deleteWord(id: String) {
        db.appDatabaseQueries.deleteWord(id)
    }

    override fun getWordCountsByStatus(): Flow<Map<String, Long>> {
        return db.appDatabaseQueries.countWordsByStatus()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.associate { it.status to it.count }
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
            status = WordStatus.valueOf(status)
        )
    }
}
