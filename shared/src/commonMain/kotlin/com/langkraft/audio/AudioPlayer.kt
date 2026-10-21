package com.langkraft.audio

import kotlinx.coroutines.flow.StateFlow

enum class AudioErrorReason {
    LOCAL_ASSET_UNAVAILABLE, STORAGE_ERROR, SOURCE_ERROR, AUTOPLAY_BLOCKED
}

data class AudioPlayerError(val requestId: Long, val reason: AudioErrorReason, val message: String)

interface AudioPlayer {
    val currentTimeMs: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val error: StateFlow<AudioPlayerError?>
    
    /** Use a new request ID per source; errors retain the ID of the source that produced them. */
    fun load(url: String, requestId: Long = 0)
    fun play()
    fun pause()
    fun seekTo(timeMs: Long)
    fun release()
}
