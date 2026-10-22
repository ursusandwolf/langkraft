package com.langkraft.data.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.langkraft.db.AppDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versions 1/2 exist in both historical branches with different columns. Inspect before upgrading. */
suspend fun initializeDatabase(driver: SqlDriver) {
    val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
        cursor.next().value; QueryResult.Value(cursor.getLong(0) ?: 0L)
    }, 0).await()
    require(version <= AppDatabase.Schema.version) { "Database was created by a newer app version" }
    suspend fun columns(table: String): Set<String> = driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
        QueryResult.Value(buildSet { while (cursor.next().value) add(requireNotNull(cursor.getString(1))) })
    }, 0).await()
    withContext(NonCancellable) {
        AppDatabase(driver).transaction {
            if (columns("Content").isEmpty()) {
                AppDatabase.Schema.create(driver).await()
            } else {
                require(columns("Content").containsAll(listOf("id", "title", "audioUrl", "createdAt"))) { "Unsupported Content schema" }
                val subtitleColumns = columns("SubtitleLine")
                if ("originalText" !in subtitleColumns) {
                    require("textDe" in subtitleColumns && "textEn" in subtitleColumns) { "Unsupported subtitle schema" }
                    driver.execute(null, "ALTER TABLE SubtitleLine RENAME COLUMN textDe TO originalText", 0).await()
                    driver.execute(null, "ALTER TABLE SubtitleLine RENAME COLUMN textEn TO translationText", 0).await()
                }
                suspend fun add(table: String, column: String, definition: String) {
                    if (column !in columns(table)) driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $definition", 0).await()
                }
                add("Content", "downloadStatus", "TEXT NOT NULL DEFAULT 'IDLE'")
                add("Content", "waveform", "TEXT NOT NULL DEFAULT '[]'")
                add("SubtitleLine", "ordinal", "INTEGER NOT NULL DEFAULT 0")
                add("Vocabulary", "lapseCount", "INTEGER NOT NULL DEFAULT 0")
                add("Vocabulary", "tags", "TEXT NOT NULL DEFAULT '[]'")
                add("Vocabulary", "lastUpdated", "INTEGER NOT NULL DEFAULT 0")
                driver.execute(null, "CREATE TABLE IF NOT EXISTS PendingSyncChange(wordId TEXT NOT NULL PRIMARY KEY, changeType TEXT NOT NULL, timestamp INTEGER NOT NULL)", 0).await()
                driver.execute(null, "CREATE TABLE IF NOT EXISTS SyncMetadata(key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)", 0).await()
                driver.execute(null, "UPDATE Content SET downloadStatus='COMPLETED' WHERE localAudioPath IS NOT NULL AND downloadStatus='IDLE'", 0).await()
            }
            val queries = AppDatabase(driver).appDatabaseQueries
            queries.selectMissingSourceReferences().awaitAsList().forEach { word ->
                queries.setSyncMetadata("source_refs:${word.id}", Json.encodeToString(listOf(word.contentId, word.subtitleLineId)))
                val localContent = word.contentId?.takeIf { queries.selectContentById(it).awaitAsOneOrNull() != null }
                val localCue = word.subtitleLineId?.takeIf { localContent != null && queries.selectSubtitleById(it).awaitAsOneOrNull()?.contentId == localContent }
                queries.updateWordReferences(localContent, localCue, word.id)
            }
            driver.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version}", 0).await()
        }
    }
    driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
}
