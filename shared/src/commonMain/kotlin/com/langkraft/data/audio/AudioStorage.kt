package com.langkraft.data.audio

import io.ktor.utils.io.ByteReadChannel

interface AudioStorage {
    /** Publish a durable, playable location only after the stream has been stored in full. */
    suspend fun store(contentId: String, source: ByteReadChannel, expectedBytes: Long?): String
}
