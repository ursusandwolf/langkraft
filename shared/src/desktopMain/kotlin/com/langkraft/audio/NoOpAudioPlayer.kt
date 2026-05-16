package com.langkraft.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NoOpAudioPlayer : AudioPlayer {
    override val currentTimeMs = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)

    override fun load(url: String) {
        // No-op
    }

    override fun play() {
        isPlaying.value = true
    }

    override fun pause() {
        isPlaying.value = false
    }

    override fun seekTo(timeMs: Long) {
        currentTimeMs.value = timeMs
    }

    override fun setPlaybackSpeed(speed: Double) {
        // No-op
    }

    override fun release() {
        // No-op
    }
}