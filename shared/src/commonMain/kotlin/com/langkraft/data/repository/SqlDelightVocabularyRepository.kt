package com.langkraft.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.async.coroutines.awaitAsList
import com.langkraft.data.database.DatabaseAccess
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import com.langkraft.domain.repository.VocabularyRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

class SqlDelightVocabularyRepository(
    private val access: DatabaseAccess,
    private val nowMillis: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val reviewRefreshMillis: Long = 60_000
) : VocabularyRepository {
    private val queries = access.database.appDatabaseQueries

    init { require(reviewRefreshMillis > 0) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getWordsToReview(): Flow<List<VocabularyWord>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(nowMillis())
            delay(reviewRefreshMillis)
        }
    }.flatMapLatest { time ->
        queries.selectVocabularyToReview(time).asFlow().map { query -> access.run(dispatcher) { query.awaitAsList() } }
    }.map { rows ->
        rows.map {
            VocabularyWord(it.id, it.word, it.lemma, it.translation, it.contextSentence.orEmpty(),
                it.contentId, it.subtitleLineId, WordStatus.valueOf(it.status))
        }
    }.distinctUntilChanged()

    override suspend fun saveWord(word: VocabularyWord) = access.run(dispatcher) {
        require(word.id.isNotBlank() && word.word.isNotBlank()) { "Word and ID must not be blank" }
        queries.upsertWord(id = word.id, word = word.word, lemma = word.lemma,
            translation = word.translation, contextSentence = word.contextSentence,
            contentId = word.contentId, subtitleLineId = word.subtitleLineId,
            addedAt = nowMillis(), status = word.status.name)
    }

    override suspend fun deleteWord(id: String) = access.run(dispatcher) { queries.deleteWord(id) }
}
