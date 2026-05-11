package com.langkraft.db

import kotlin.Long
import kotlin.String

public data class SubtitleLine(
  public val id: String,
  public val contentId: String,
  public val startMs: Long,
  public val endMs: Long,
  public val textDe: String,
  public val textEn: String?,
)
