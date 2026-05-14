package com.langkraft.backend.db

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction

interface VocabularySyncRepository {
    fun sync(userId: String, clientChanges: List<VocabularyWord>, lastSyncTimestamp: Long): List<VocabularyWord>
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
        this[VocabularySync.tags] = word.tags.joinToString(",")
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
        tags = if (this[VocabularySync.tags].isEmpty()) emptyList() else this[VocabularySync.tags].split(","),
        status = WordStatus.valueOf(this[VocabularySync.status]),
        lastUpdated = this[VocabularySync.lastUpdated]
    )

    override fun sync(userId: String, clientChanges: List<VocabularyWord>, lastSyncTimestamp: Long): List<VocabularyWord> = transaction(db) {
        // 1. Process client changes
        val existingIds = VocabularySync
            .slice(VocabularySync.id)
            .select { VocabularySync.id inList clientChanges.map { it.id } }
            .map { it[VocabularySync.id] }
            .toSet()

        val toInsert = clientChanges.filter { it.id !in existingIds }
        val toUpdate = clientChanges.filter { it.id in existingIds }

        if (toInsert.isNotEmpty()) {
            VocabularySync.batchInsert(toInsert) { word ->
                this[VocabularySync.id] = word.id
                applyWord(word, userId)
            }
        }

        val existingTimestamps = VocabularySync
            .slice(VocabularySync.id, VocabularySync.lastUpdated)
            .select { VocabularySync.id inList toUpdate.map { it.id } }
            .associate { it[VocabularySync.id] to it[VocabularySync.lastUpdated] }

        toUpdate.forEach { word ->
            val existingLastUpdated = existingTimestamps[word.id] ?: 0L

            if (word.lastUpdated > existingLastUpdated) {
                VocabularySync.update({ VocabularySync.id eq word.id }) {
                    it.applyWord(word, userId)
                }
            }
        }

        // 2. Find server changes since lastSyncTimestamp that were NOT in the clientChanges list
        val clientIds = clientChanges.map { it.id }.toSet()
        
        VocabularySync.select { 
            (VocabularySync.userId eq userId) and (VocabularySync.lastUpdated greater lastSyncTimestamp) 
        }.filter { 
            it[VocabularySync.id] !in clientIds 
        }.map { it.toVocabularyWord() }
    }
}
