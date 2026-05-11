package com.langkraft.db

import kotlin.Long
import kotlin.String

public data class CountWordsByStatus(
  public val status: String,
  public val count: Long,
)
