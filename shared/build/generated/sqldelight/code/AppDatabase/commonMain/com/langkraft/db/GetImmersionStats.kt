package com.langkraft.db

import kotlin.Long

public data class GetImmersionStats(
  public val totalContent: Long,
  public val totalDurationSeconds: Long?,
)
