package com.langkraft.db

import kotlin.Long
import kotlin.String

public data class Content(
  public val id: String,
  public val title: String,
  public val audioUrl: String,
  public val localAudioPath: String?,
  public val sourceUrl: String,
  public val durationSeconds: Long,
  public val createdAt: Long,
)
