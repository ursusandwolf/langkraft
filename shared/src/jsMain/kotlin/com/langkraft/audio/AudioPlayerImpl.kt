package com.langkraft.audio

import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAudioElement

actual class AudioPlayerImpl : AudioPlayer {
    private val audio = window.document.createElement("audio") as HTMLAudioElement
    
    private val _currentTimeMs = MutableStateFlow(0L)
    actual override val currentTimeMs: StateFlow<Long> = _currentTimeMs
    
    private val _isPlaying = MutableStateFlow(false)
    actual override val isPlaying: StateFlow<Boolean> = _isPlaying

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        audio.onplay = { _isPlaying.value = true; startTimer() }
        audio.onpause = { _isPlaying.value = false; stopTimer() }
        audio.onended = { _isPlaying.value = false; stopTimer() }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                _currentTimeMs.value = (audio.currentTime * 1000).toLong()
                delay(100)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
    }

    actual override fun load(url: String) {
        audio.src = url
        audio.load()
    }

    actual override fun play() {
        audio.play()
    }

    actual override fun pause() {
        audio.pause()
    }

    actual override fun seekTo(timeMs: Long) {
        audio.currentTime = timeMs / 1000.0
    }

    actual override fun setPlaybackSpeed(speed: Double) {
        audio.playbackRate = speed
    }

    actual override fun release() {
        stopTimer()
        audio.pause()
        audio.src = ""
    }
}
