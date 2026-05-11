package com.langkraft.backend.db

import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class BackendVocabularyRepository {

    fun sync(userId: String, clientChanges: List<VocabularyWord>, lastSyncTimestamp: Long): List<VocabularyWord> = transaction {
        // 1. Process client changes (Upsert)
        clientChanges.forEach { word ->
            val updated = VocabularySync.update({ VocabularySync.id eq word.id }) {
                it[VocabularySync.userId] = userId
                it[VocabularySync.word] = word.word
                it[VocabularySync.lemma] = word.lemma
                it[VocabularySync.translation] = word.translation
                it[VocabularySync.contextSentence] = word.contextSentence
                it[VocabularySync.contentId] = word.contentId
                it[VocabularySync.subtitleLineId] = word.subtitleLineId
                it[VocabularySync.addedAt] = word.addedAt
                it[VocabularySync.status] = word.status.name
                it[VocabularySync.nextReviewAt] = word.nextReviewMs
                it[VocabularySync.intervalDays] = word.intervalDays.toLong()
                it[VocabularySync.easeFactor] = word.easeFactor
                it[VocabularySync.lastUpdated] = word.lastUpdated
            }
            
            if (updated == 0) {
                VocabularySync.insert {
                    it[VocabularySync.id] = word.id
                    it[VocabularySync.userId] = userId
                    it[VocabularySync.word] = word.word
                    it[VocabularySync.lemma] = word.lemma
                    it[VocabularySync.translation] = word.translation
                    it[VocabularySync.contextSentence] = word.contextSentence
                    it[VocabularySync.contentId] = word.contentId
                    it[VocabularySync.subtitleLineId] = word.subtitleLineId
                    it[VocabularySync.addedAt] = word.addedAt
                    it[VocabularySync.status] = word.status.name
                    it[VocabularySync.nextReviewAt] = word.nextReviewMs
                    it[VocabularySync.intervalDays] = word.intervalDays.toLong()
                    it[VocabularySync.easeFactor] = word.easeFactor
                    it[VocabularySync.lastUpdated] = word.lastUpdated
                }
            }
        }

        // 2. Find server changes since lastSyncTimestamp that were NOT in the clientChanges list
        // (to avoid sending back what client just sent)
        val clientIds = clientChanges.map { it.id }.toSet()
        
        VocabularySync.select { 
            (VocabularySync.userId eq userId) and (VocabularySync.lastUpdated greater lastSyncTimestamp) 
        }.filter { 
            it[VocabularySync.id] !in clientIds 
        }.map {
            VocabularyWord(
                id = it[VocabularySync.id],
                word = it[VocabularySync.word],
                lemma = it[VocabularySync.lemma],
                translation = it[VocabularySync.translation],
                contextSentence = it[VocabularySync.contextSentence],
                contentId = it[VocabularySync.contentId],
                subtitleLineId = it[VocabularySync.subtitleLineId],
                addedAt = it[VocabularySync.addedAt],
                nextReviewMs = it[VocabularySync.nextReviewAt],
                intervalDays = it[VocabularySync.intervalDays].toInt(),
                easeFactor = it[VocabularySync.easeFactor],
                status = WordStatus.valueOf(it[VocabularySync.status]),
                lastUpdated = it[VocabularySync.lastUpdated]
            )
        }
    }
}
