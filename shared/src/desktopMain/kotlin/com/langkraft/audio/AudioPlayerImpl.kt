package com.langkraft.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class AudioPlayerImpl : AudioPlayer {
    private val _currentTimeMs = MutableStateFlow(0L)
    actual override val currentTimeMs: StateFlow<Long> = _currentTimeMs
    
    private val _isPlaying = MutableStateFlow(false)
    actual override val isPlaying: StateFlow<Boolean> = _isPlaying

    actual override fun load(url: String) {
        println("Desktop Mock: Loading audio from $url")
    }

    actual override fun play() {
        _isPlaying.value = true
        println("Desktop Mock: Playing")
    }

    actual override fun pause() {
        _isPlaying.value = false
        println("Desktop Mock: Paused")
    }

    actual override fun seekTo(timeMs: Long) {
        _currentTimeMs.value = timeMs
        println("Desktop Mock: Seeking to $timeMs")
    }

    actual override fun release() {
        println("Desktop Mock: Released")
    }
}
