package com.langkraft.audio

import kotlinx.coroutines.flow.StateFlow

interface AudioPlayer {
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
