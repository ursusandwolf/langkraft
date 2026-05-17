package com.langkraft.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import java.io.File

actual class AudioPlayerImpl : AudioPlayer {
    private val _currentTimeMs = MutableStateFlow(0L)
    actual override val currentTimeMs: StateFlow<Long> = _currentTimeMs
    
    private val _isPlaying = MutableStateFlow(false)
    actual override val isPlaying: StateFlow<Boolean> = _isPlaying

    private val mediaPlayerComponent = AudioPlayerComponent()
    private val mediaPlayer = mediaPlayerComponent.mediaPlayer()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var progressJob: Job? = null

    init {
        scope.launch {
            while (isActive) {
                if (isPlaying.value) {
                    _currentTimeMs.value = mediaPlayer.status().time()
                }
                delay(100)
            }
        }
    }

    actual override fun load(url: String) {
        val mediaUrl = if (url.startsWith("http")) url else File(url).absolutePath
        mediaPlayer.media().play(mediaUrl)
        _isPlaying.value = true
    }

    actual override fun play() {
        if (!mediaPlayer.status().isPlaying()) {
            mediaPlayer.controls().play()
            _isPlaying.value = true
        }
    }

    actual override fun pause() {
        if (mediaPlayer.status().isPlaying()) {
            mediaPlayer.controls().pause()
            _isPlaying.value = false
        }
    }

    actual override fun seekTo(timeMs: Long) {
        mediaPlayer.controls().setTime(timeMs)
        _currentTimeMs.value = timeMs
    }

    actual override fun setPlaybackSpeed(speed: Double) {
        mediaPlayer.controls().setRate(speed.toFloat())
    }

    actual override fun release() {
        mediaPlayer.controls().stop()
        mediaPlayer.release()
        _isPlaying.value = false
        _currentTimeMs.value = 0
    }
}
