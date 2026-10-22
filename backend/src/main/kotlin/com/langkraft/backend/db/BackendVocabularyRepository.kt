package com.langkraft.backend.db

import com.langkraft.domain.model.SyncEntry
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import com.langkraft.domain.model.SyncResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction

interface VocabularySyncRepository {
    fun sync(userId: String, clientChanges: List<SyncEntry>, lastSyncTimestamp: Long): List<SyncEntry>
    fun synchronize(userId: String, clientChanges: List<SyncEntry>, lastSyncTimestamp: Long): SyncResponse =
        SyncResponse(System.currentTimeMillis(), sync(userId, clientChanges, lastSyncTimestamp))
}

class BackendVocabularyRepository(private val db: Database? = null) : VocabularySyncRepository {

    private fun UpdateBuilder<*>.applyWord(word: VocabularyWord, userId: String) {
        this[VocabularySync.userId] = userId
        this[VocabularySync.word] = word.word
        this[VocabularySync.lemma] = word.lemma
        this[VocabularySync.translation] = word.translation
        this[VocabularySync.contextSentence] = word.contextSentence
        this[VocabularySync.contentId] = word.contentId
        this[VocabularySync.subtitleLineId] = word.subtitleLineId
        this[VocabularySync.addedAt] = word.addedAt
        this[VocabularySync.status] = word.status.name
        this[VocabularySync.nextReviewAt] = word.nextReviewMs
        this[VocabularySync.intervalDays] = word.intervalDays.toLong()
        this[VocabularySync.easeFactor] = word.easeFactor
        this[VocabularySync.lapseCount] = word.lapseCount
        this[VocabularySync.tags] = Json.encodeToString(word.tags)
        this[VocabularySync.lastUpdated] = word.lastUpdated
    }

    private fun ResultRow.toVocabularyWord() = VocabularyWord(
        id = this[VocabularySync.id],
        word = this[VocabularySync.word],
        lemma = this[VocabularySync.lemma],
        translation = this[VocabularySync.translation],
        contextSentence = this[VocabularySync.contextSentence],
        contentId = this[VocabularySync.contentId],
        subtitleLineId = this[VocabularySync.subtitleLineId],
        addedAt = this[VocabularySync.addedAt],
        nextReviewMs = this[VocabularySync.nextReviewAt],
        intervalDays = this[VocabularySync.intervalDays].toInt(),
        easeFactor = this[VocabularySync.easeFactor],
        lapseCount = this[VocabularySync.lapseCount],
        tags = runCatching { Json.decodeFromString<List<String>>(this[VocabularySync.tags]) }
            .getOrElse { this[VocabularySync.tags].split(",").filter { it.isNotBlank() } },
        status = WordStatus.valueOf(this[VocabularySync.status]),
        lastUpdated = this[VocabularySync.lastUpdated]
    )

    override fun sync(userId: String, clientChanges: List<SyncEntry>, lastSyncTimestamp: Long): List<SyncEntry> =
        synchronize(userId, clientChanges, lastSyncTimestamp).serverChanges

    override fun synchronize(userId: String, clientChanges: List<SyncEntry>, lastSyncTimestamp: Long): SyncResponse = transaction(db) {
        val maximum = VocabularySync.serverUpdated.max()
        var cursor = VocabularySync.slice(maximum).select { VocabularySync.userId eq userId }.single()[maximum] ?: 0L
        val applied = mutableSetOf<String>()
        for (entry in clientChanges) {
            require(entry.changeType in setOf("UPSERT", "DELETE")) { "Unsupported sync change" }
            val existing = VocabularySync.select { VocabularySync.id eq entry.word.id }.singleOrNull()
            require(existing == null || existing[VocabularySync.userId] == userId) { "Word belongs to another account" }
            if (existing == null || entry.word.lastUpdated > existing[VocabularySync.lastUpdated] ||
                (entry.changeType == "DELETE" && entry.word.lastUpdated == existing[VocabularySync.lastUpdated])) {
                cursor = maxOf(System.currentTimeMillis(), cursor + 1)
                if (existing == null) VocabularySync.insert {
                    it[id] = entry.word.id
                    it.applyWord(entry.word, userId)
                    it[serverUpdated] = cursor
                    it[deleted] = entry.changeType == "DELETE"
                } else VocabularySync.update({ VocabularySync.id eq entry.word.id }) {
                    it.applyWord(entry.word, userId)
                    it[serverUpdated] = cursor
                    it[deleted] = entry.changeType == "DELETE"
                }
                applied += entry.word.id
            }
        }
        val changes = VocabularySync.select {
            (VocabularySync.userId eq userId) and (VocabularySync.serverUpdated greater lastSyncTimestamp)
        }.filter { it[VocabularySync.id] !in applied }.map {
            SyncEntry(it.toVocabularyWord(), if (it[VocabularySync.deleted]) "DELETE" else "UPSERT")
        }
        SyncResponse(cursor, changes)
    }
}
