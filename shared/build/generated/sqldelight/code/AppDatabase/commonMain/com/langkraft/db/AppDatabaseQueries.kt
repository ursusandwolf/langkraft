package com.langkraft.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Double
import kotlin.Long
import kotlin.String

public class AppDatabaseQueries(
  driver: SqlDriver,
) : TransacterImpl(driver) {
  public fun <T : Any> selectAllContent(mapper: (
    id: String,
    title: String,
    audioUrl: String,
    localAudioPath: String?,
    sourceUrl: String,
    durationSeconds: Long,
    createdAt: Long,
  ) -> T): Query<T> = Query(-2_008_814_909, arrayOf("Content"), driver, "AppDatabase.sq",
      "selectAllContent", "SELECT * FROM Content ORDER BY createdAt DESC") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!
    )
  }

  public fun selectAllContent(): Query<Content> = selectAllContent { id, title, audioUrl,
      localAudioPath, sourceUrl, durationSeconds, createdAt ->
    Content(
      id,
      title,
      audioUrl,
      localAudioPath,
      sourceUrl,
      durationSeconds,
      createdAt
    )
  }

  public fun <T : Any> selectSubtitlesForContent(contentId: String, mapper: (
    id: String,
    contentId: String,
    startMs: Long,
    endMs: Long,
    textDe: String,
    textEn: String?,
  ) -> T): Query<T> = SelectSubtitlesForContentQuery(contentId) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getLong(2)!!,
      cursor.getLong(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)
    )
  }

  public fun selectSubtitlesForContent(contentId: String): Query<SubtitleLine> =
      selectSubtitlesForContent(contentId) { id, contentId_, startMs, endMs, textDe, textEn ->
    SubtitleLine(
      id,
      contentId_,
      startMs,
      endMs,
      textDe,
      textEn
    )
  }

  public fun <T : Any> selectVocabularyToReview(nextReviewAt: Long?, mapper: (
    id: String,
    word: String,
    lemma: String?,
    translation: String?,
    contextSentence: String?,
    contentId: String?,
    subtitleLineId: String?,
    addedAt: Long,
    nextReviewAt: Long?,
    intervalDays: Long,
    easeFactor: Double,
    status: String,
  ) -> T): Query<T> = SelectVocabularyToReviewQuery(nextReviewAt) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5),
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9)!!,
      cursor.getDouble(10)!!,
      cursor.getString(11)!!
    )
  }

  public fun selectVocabularyToReview(nextReviewAt: Long?): Query<Vocabulary> =
      selectVocabularyToReview(nextReviewAt) { id, word, lemma, translation, contextSentence,
      contentId, subtitleLineId, addedAt, nextReviewAt_, intervalDays, easeFactor, status ->
    Vocabulary(
      id,
      word,
      lemma,
      translation,
      contextSentence,
      contentId,
      subtitleLineId,
      addedAt,
      nextReviewAt_,
      intervalDays,
      easeFactor,
      status
    )
  }

  public fun <T : Any> countWordsByStatus(mapper: (status: String, count: Long) -> T): Query<T> =
      Query(1_272_017_266, arrayOf("Vocabulary"), driver, "AppDatabase.sq", "countWordsByStatus",
      "SELECT status, COUNT(*) AS count FROM Vocabulary GROUP BY status") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!
    )
  }

  public fun countWordsByStatus(): Query<CountWordsByStatus> = countWordsByStatus { status, count ->
    CountWordsByStatus(
      status,
      count
    )
  }

  public fun <T : Any> getImmersionStats(mapper: (totalContent: Long,
      totalDurationSeconds: Long?) -> T): Query<T> = Query(260_184_891, arrayOf("Content"), driver,
      "AppDatabase.sq", "getImmersionStats",
      "SELECT COUNT(*) AS totalContent, SUM(durationSeconds) AS totalDurationSeconds FROM Content") {
      cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getLong(1)
    )
  }

  public fun getImmersionStats(): Query<GetImmersionStats> = getImmersionStats { totalContent,
      totalDurationSeconds ->
    GetImmersionStats(
      totalContent,
      totalDurationSeconds
    )
  }

  public fun getWordsAddedRecently(addedAt: Long): Query<Long> =
      GetWordsAddedRecentlyQuery(addedAt) { cursor ->
    cursor.getLong(0)!!
  }

  public fun insertContent(
    id: String,
    title: String,
    audioUrl: String,
    localAudioPath: String?,
    sourceUrl: String,
    durationSeconds: Long,
    createdAt: Long,
  ) {
    driver.execute(930_476_241, """
        |INSERT OR REPLACE INTO Content(id, title, audioUrl, localAudioPath, sourceUrl, durationSeconds, createdAt)
        |VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 7) {
          bindString(0, id)
          bindString(1, title)
          bindString(2, audioUrl)
          bindString(3, localAudioPath)
          bindString(4, sourceUrl)
          bindLong(5, durationSeconds)
          bindLong(6, createdAt)
        }
    notifyQueries(930_476_241) { emit ->
      emit("Content")
    }
  }

  public fun insertSubtitleLine(
    id: String,
    contentId: String,
    startMs: Long,
    endMs: Long,
    textDe: String,
    textEn: String?,
  ) {
    driver.execute(1_935_728_340, """
        |INSERT OR REPLACE INTO SubtitleLine(id, contentId, startMs, endMs, textDe, textEn)
        |VALUES (?, ?, ?, ?, ?, ?)
        """.trimMargin(), 6) {
          bindString(0, id)
          bindString(1, contentId)
          bindLong(2, startMs)
          bindLong(3, endMs)
          bindString(4, textDe)
          bindString(5, textEn)
        }
    notifyQueries(1_935_728_340) { emit ->
      emit("SubtitleLine")
    }
  }

  public fun upsertWord(
    id: String,
    word: String,
    lemma: String?,
    translation: String?,
    contextSentence: String?,
    contentId: String?,
    subtitleLineId: String?,
    addedAt: Long,
    status: String,
  ) {
    driver.execute(-2_079_566_552, """
        |INSERT OR REPLACE INTO Vocabulary(id, word, lemma, translation, contextSentence, contentId, subtitleLineId, addedAt, status)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 9) {
          bindString(0, id)
          bindString(1, word)
          bindString(2, lemma)
          bindString(3, translation)
          bindString(4, contextSentence)
          bindString(5, contentId)
          bindString(6, subtitleLineId)
          bindLong(7, addedAt)
          bindString(8, status)
        }
    notifyQueries(-2_079_566_552) { emit ->
      emit("Vocabulary")
    }
  }

  public fun updateLocalAudioPath(localAudioPath: String?, id: String) {
    driver.execute(2_067_516_584, """UPDATE Content SET localAudioPath = ? WHERE id = ?""", 2) {
          bindString(0, localAudioPath)
          bindString(1, id)
        }
    notifyQueries(2_067_516_584) { emit ->
      emit("Content")
    }
  }

  private inner class SelectSubtitlesForContentQuery<out T : Any>(
    public val contentId: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("SubtitleLine", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("SubtitleLine", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
        driver.executeQuery(-1_795_802_528,
        """SELECT * FROM SubtitleLine WHERE contentId = ? ORDER BY startMs ASC""", mapper, 1) {
      bindString(0, contentId)
    }

    override fun toString(): String = "AppDatabase.sq:selectSubtitlesForContent"
  }

  private inner class SelectVocabularyToReviewQuery<out T : Any>(
    public val nextReviewAt: Long?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("Vocabulary", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("Vocabulary", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
        driver.executeQuery(-2_005_041_948,
        """SELECT * FROM Vocabulary WHERE nextReviewAt <= ? OR nextReviewAt IS NULL ORDER BY nextReviewAt ASC""",
        mapper, 1) {
      bindLong(0, nextReviewAt)
    }

    override fun toString(): String = "AppDatabase.sq:selectVocabularyToReview"
  }

  private inner class GetWordsAddedRecentlyQuery<out T : Any>(
    public val addedAt: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("Vocabulary", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("Vocabulary", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
        driver.executeQuery(1_503_008_870, """SELECT COUNT(*) FROM Vocabulary WHERE addedAt >= ?""",
        mapper, 1) {
      bindLong(0, addedAt)
    }

    override fun toString(): String = "AppDatabase.sq:getWordsAddedRecently"
  }
}
