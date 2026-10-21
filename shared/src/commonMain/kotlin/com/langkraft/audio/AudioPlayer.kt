package com.langkraft.audio

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class AudioErrorReason { LOCAL_ASSET_UNAVAILABLE, STORAGE_ERROR, SOURCE_ERROR, AUTOPLAY_BLOCKED }
data class AudioPlayerError(val requestId: Long, val reason: AudioErrorReason, val message: String)
private val noAudioError = MutableStateFlow<AudioPlayerError?>(null)

interface AudioPlayer {
    val error: StateFlow<AudioPlayerError?> get() = noAudioError
    val currentTimeMs: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>

    fun load(url: String)
    fun play()
    fun pause()
    fun seekTo(timeMs: Long)
    fun setPlaybackSpeed(speed: Double)
    fun release()
}

expect class AudioPlayerImpl() : AudioPlayer {
    override val currentTimeMs: StateFlow<Long>
    override val isPlaying: StateFlow<Boolean>

    override fun load(url: String)
    override fun play()
    override fun pause()
    override fun seekTo(timeMs: Long)
    override fun setPlaybackSpeed(speed: Double)
    override fun release()
}
