package com.langkraft.backend.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Users : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 255)
    
    override val primaryKey = PrimaryKey(id)
}

object VocabularySync : Table("vocabulary_sync") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(Users.id)
    val word = varchar("word", 255)
    val lemma = varchar("lemma", 255).nullable()
    val translation = varchar("translation", 1024).nullable()
    val contextSentence = text("context_sentence")
    val contentId = varchar("content_id", 36).nullable()
    val subtitleLineId = varchar("subtitle_line_id", 36).nullable()
    val addedAt = long("added_at")
    val status = varchar("status", 50)
    val nextReviewAt = long("next_review_at")
    val intervalDays = long("interval_days")
    val easeFactor = double("ease_factor")
    val lapseCount = integer("lapse_count")
    val tags = text("tags")
    val lastUpdated = long("last_updated")

    override val primaryKey = PrimaryKey(id)
}
