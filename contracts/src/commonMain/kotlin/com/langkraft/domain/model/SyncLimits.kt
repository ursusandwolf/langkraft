package com.langkraft.domain.model

/** Request limits shared by the server and clients that partition their offline queue. */
object SyncLimits {
    const val MAX_BODY_BYTES = 1024 * 1024
    const val MAX_BATCH_SIZE = 500
}
