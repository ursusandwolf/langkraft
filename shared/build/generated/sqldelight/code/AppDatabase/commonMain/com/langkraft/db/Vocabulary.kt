package com.langkraft.db

import kotlin.Double
import kotlin.Long
import kotlin.String

public data class Vocabulary(
  public val id: String,
  public val word: String,
  public val lemma: String?,
  public val translation: String?,
  public val contextSentence: String?,
  public val contentId: String?,
  public val subtitleLineId: String?,
  public val addedAt: Long,
  public val nextReviewAt: Long?,
  public val intervalDays: Long,
  public val easeFactor: Double,
  public val status: String,
)
