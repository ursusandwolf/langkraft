package com.langkraft.data.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.langkraft.domain.model.SyncEntry
import com.langkraft.domain.model.SyncRequest
import com.langkraft.domain.model.SyncResponse
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.io.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendEncodedPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getWordsToReview(): Flow<List<VocabularyWord>> = reviewClock().flatMapLatest { now ->
        db.appDatabaseQueries.selectVocabularyToReview(now).asFlowList { it.toDomain() }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getReviewCount(): Flow<Int> = reviewClock().flatMapLatest { now ->
        db.appDatabaseQueries.countVocabularyToReview(now).asFlowOne { it.toInt() }
    }.distinctUntilChanged()

    private fun reviewClock(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            emit(Clock.System.now().toEpochMilliseconds())
            delay(60_000)
        }
    }

    override suspend fun saveWord(word: VocabularyWord) = access.run(dispatcher) {
        require(word.id.isNotBlank() && word.word.isNotBlank()) { "Word and ID must not be blank" }
        db.appDatabaseQueries.transaction {
            val revision = nextRevision(word.id)
            upsertLocalWord(word, revision)
            db.appDatabaseQueries.insertPendingChange(
                wordId = word.id,
                changeType = "UPSERT",
                timestamp = revision
            )
        }
    }

    override suspend fun deleteWord(id: String) = access.run(dispatcher) {
        db.appDatabaseQueries.transaction {
            val revision = nextRevision(id)
            db.appDatabaseQueries.deleteWord(id)
            db.appDatabaseQueries.insertPendingChange(
                wordId = id,
                changeType = "DELETE",
                timestamp = revision
            )
        }
    }

    override suspend fun sync(lastSyncTimestamp: Long): Long {
        val pendingChanges = access.run(dispatcher) {
            db.appDatabaseQueries.getAllPendingChanges().awaitAsList()
        }
        if (pendingChanges.isEmpty()) {
            return fetchServerChanges(lastSyncTimestamp, emptyList(), emptyList())
        }

        val upsertIds = pendingChanges.filter { it.changeType == "UPSERT" }.map { it.wordId }
        val wordsById = loadWordsById(upsertIds)
        val clientEntries = pendingChanges.mapNotNull { change ->
            toClientSyncEntry(change, wordsById)
        }
        return fetchServerChanges(lastSyncTimestamp, clientEntries, pendingChanges)
    }

    /** Monotonic revision: max(now, pending+1, local lastUpdated+1). */
    private suspend fun nextRevision(wordId: String): Long {
        val pendingTs = db.appDatabaseQueries.selectPendingChange(wordId).awaitAsOneOrNull()?.timestamp ?: 0L
        val localTs = db.appDatabaseQueries.selectWordById(wordId).awaitAsOneOrNull()?.lastUpdated ?: 0L
        return maxOf(Clock.System.now().toEpochMilliseconds(), pendingTs + 1, localTs + 1)
    }

    private suspend fun loadWordsById(ids: List<String>): Map<String, VocabularyWord> {
        if (ids.isEmpty()) return emptyMap()
        return access.run(dispatcher) {
            db.appDatabaseQueries.selectWordsByIds(ids).awaitAsList()
        }.associate { it.id to it.toDomain() }
    }

    private suspend fun toClientSyncEntry(
        change: com.langkraft.db.PendingSyncChange,
        wordsById: Map<String, VocabularyWord>
    ): SyncEntry? {
        if (change.changeType == "DELETE") {
            return SyncEntry(word = deletedSyncPlaceholder(change.wordId, change.timestamp), changeType = "DELETE")
        }
        val word = wordsById[change.wordId] ?: return null
        val references = access.run(dispatcher) {
            db.appDatabaseQueries.getSyncMetadata("source_refs:${word.id}").awaitAsOneOrNull()
                ?.let { json.decodeFromString<List<String?>>(it) }
        }
        return SyncEntry(
            word = word.copy(
                contentId = word.contentId ?: references?.getOrNull(0),
                subtitleLineId = word.subtitleLineId ?: references?.getOrNull(1)
            ),
            changeType = "UPSERT"
        )
    }

    private fun deletedSyncPlaceholder(wordId: String, timestamp: Long): VocabularyWord =
        VocabularyWord(
            id = wordId,
            word = "",
            lemma = "",
            translation = "",
            contextSentence = "",
            contentId = "",
            subtitleLineId = "",
            addedAt = 0,
            nextReviewMs = 0,
            intervalDays = 0,
            easeFactor = 0.0,
            status = WordStatus.NEW,
            lapseCount = 0,
            tags = emptyList(),
            lastUpdated = timestamp
        )

    private suspend fun upsertLocalWord(word: VocabularyWord, revision: Long) {
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
    }

    /**
     * Apply one server change. Skip if a newer local pending change exists that we did not just send.
     * Remote content/subtitle ids are stored in sync metadata when the video is not local.
     */
    private suspend fun applyServerEntry(
        entry: SyncEntry,
        sentChanges: List<com.langkraft.db.PendingSyncChange>
    ) {
        val pending = db.appDatabaseQueries.selectPendingChange(entry.word.id).awaitAsOneOrNull()
        val sent = sentChanges.firstOrNull { it.wordId == entry.word.id }
        if (pending != null && pending != sent) return

        if (entry.changeType == "DELETE") {
            db.appDatabaseQueries.deleteWord(entry.word.id)
            db.appDatabaseQueries.deleteSyncMetadata("source_refs:${entry.word.id}")
            return
        }

        val word = entry.word
        db.appDatabaseQueries.setSyncMetadata(
            "source_refs:${word.id}",
            json.encodeToString(listOf(word.contentId, word.subtitleLineId))
        )
        val localContent = word.contentId?.takeIf {
            db.appDatabaseQueries.selectContentById(it).awaitAsOneOrNull() != null
        }
        val localSubtitle = word.subtitleLineId?.takeIf {
            localContent != null &&
                db.appDatabaseQueries.selectSubtitleById(it).awaitAsOneOrNull()?.contentId == localContent
        }
        db.appDatabaseQueries.upsertWord(
            id = word.id,
            word = word.word,
            lemma = word.lemma,
            translation = word.translation,
            contextSentence = word.contextSentence,
            contentId = localContent,
            subtitleLineId = localSubtitle,
            addedAt = word.addedAt,
            nextReviewAt = word.nextReviewMs,
            intervalDays = word.intervalDays.toLong(),
            easeFactor = word.easeFactor,
            status = word.status.name,
            lapseCount = word.lapseCount.toLong(),
            tags = json.encodeToString(word.tags),
            lastUpdated = word.lastUpdated
        )
    }

    private suspend fun fetchServerChanges(
        lastSyncTimestamp: Long, 
        clientChanges: List<SyncEntry>,
        processedIds: List<com.langkraft.db.PendingSyncChange>
    ): Long {
        return try {
            val httpResponse = httpClient.post(baseUrl) {
                url { appendEncodedPathSegments("api", "sync") }
                contentType(ContentType.Application.Json)
                setBody(SyncRequest(lastSyncTimestamp, clientChanges))
            }
            check(httpResponse.status.isSuccess()) {
                when (httpResponse.status) {
                    HttpStatusCode.Unauthorized -> "Please sign in through Account to sync (HTTP 401)."
                    HttpStatusCode.Forbidden -> "Sync was denied (HTTP 403)."
                    else -> "Sync service returned HTTP ${httpResponse.status.value}."
                }
            }
            val response = httpResponse.body<SyncResponse>()

            access.run(dispatcher) {
                db.transaction {
                    response.serverChanges.forEach { entry ->
                        applyServerEntry(entry, processedIds)
                    }
                    processedIds.forEach { change ->
                        db.appDatabaseQueries.deleteAcknowledgedChange(
                            change.wordId,
                            change.timestamp,
                            change.changeType
                        )
                    }
                }
            }
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
