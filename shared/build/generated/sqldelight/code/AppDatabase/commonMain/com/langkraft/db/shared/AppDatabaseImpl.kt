package com.langkraft.db.shared

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.langkraft.db.AppDatabase
import com.langkraft.db.AppDatabaseQueries
import kotlin.Long
import kotlin.Unit
import kotlin.reflect.KClass

internal val KClass<AppDatabase>.schema: SqlSchema<QueryResult.Value<Unit>>
  get() = AppDatabaseImpl.Schema

internal fun KClass<AppDatabase>.newInstance(driver: SqlDriver): AppDatabase =
    AppDatabaseImpl(driver)

private class AppDatabaseImpl(
  driver: SqlDriver,
) : TransacterImpl(driver), AppDatabase {
  override val appDatabaseQueries: AppDatabaseQueries = AppDatabaseQueries(driver)

  public object Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long
      get() = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(null, """
          |CREATE TABLE Content (
          |    id TEXT NOT NULL PRIMARY KEY,
          |    title TEXT NOT NULL,
          |    audioUrl TEXT NOT NULL,         -- Remote URL
          |    localAudioPath TEXT,            -- Local path for offline mode
          |    sourceUrl TEXT NOT NULL,        -- Original YouTube URL
          |    durationSeconds INTEGER NOT NULL DEFAULT 0,
          |    createdAt INTEGER NOT NULL      -- Timestamp
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE SubtitleLine (
          |    id TEXT NOT NULL PRIMARY KEY,
          |    contentId TEXT NOT NULL,
          |    startMs INTEGER NOT NULL,
          |    endMs INTEGER NOT NULL,
          |    textDe TEXT NOT NULL,           -- Original German text
          |    textEn TEXT,                    -- Optional English translation
          |    FOREIGN KEY (contentId) REFERENCES Content(id) ON DELETE CASCADE
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE Vocabulary (
          |    id TEXT NOT NULL PRIMARY KEY,
          |    word TEXT NOT NULL,             -- The word itself (e.g., "Häuser")
          |    lemma TEXT,                     -- Dictionary form (e.g., "Haus")
          |    translation TEXT,               -- User or AI provided translation
          |    contextSentence TEXT,           -- The specific sentence where it was found
          |    contentId TEXT,                 -- Reference to source content
          |    subtitleLineId TEXT,            -- Reference to specific timestamp
          |    addedAt INTEGER NOT NULL,
          |    
          |    -- Spaced Repetition (SRS) data
          |    nextReviewAt INTEGER,           -- Timestamp for next review
          |    intervalDays INTEGER NOT NULL DEFAULT 0,
          |    easeFactor REAL NOT NULL DEFAULT 2.5,
          |    status TEXT NOT NULL DEFAULT 'NEW', -- NEW, LEARNING, MASTERED
          |    
          |    FOREIGN KEY (contentId) REFERENCES Content(id) ON DELETE SET NULL,
          |    FOREIGN KEY (subtitleLineId) REFERENCES SubtitleLine(id) ON DELETE SET NULL
          |)
          """.trimMargin(), 0)
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
  }
}
