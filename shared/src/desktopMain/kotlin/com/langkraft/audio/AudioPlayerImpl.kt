package com.langkraft.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javazoom.jl.player.Player
import java.io.BufferedInputStream
import java.net.URL
import java.io.File
import java.io.FileInputStream

actual class AudioPlayerImpl : AudioPlayer {
    private val _currentTimeMs = MutableStateFlow(0L)
    actual override val currentTimeMs: StateFlow<Long> = _currentTimeMs
    
    private val _isPlaying = MutableStateFlow(false)
    actual override val isPlaying: StateFlow<Boolean> = _isPlaying

    private var player: Player? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var currentUrl: String? = null
    private var lastStartMs: Long = 0

    actual override fun load(url: String) {
        stopCurrent()
        currentUrl = url
        _currentTimeMs.value = 0
        println("Desktop: Loading audio from $url")
    }

    actual override fun play() {
        val url = currentUrl ?: return
        if (_isPlaying.value) return

        _isPlaying.value = true
        playbackJob = scope.launch {
            try {
                val inputStream = if (url.startsWith("http")) {
                    URL(url).openStream()
                } else {
                    FileInputStream(File(url))
                }
                
                BufferedInputStream(inputStream).use { bis ->
                    player = Player(bis)
                    
                    // JLayer Player.play() is blocking
                    // Note: JLayer doesn't support easy seeking, so we just play from start for now.
                    // Real implementation would need a more robust library like VLCJ or JavaFX.
                    
                    launch(Dispatchers.Default) {
                        val startTime = System.currentTimeMillis()
                        while (isActive && _isPlaying.value) {
                            _currentTimeMs.value = lastStartMs + (System.currentTimeMillis() - startTime)
                            delay(100)
                        }
                    }
                    
                    player?.play()
                }
            } catch (e: Exception) {
                println("Playback error: ${e.message}")
            } finally {
                _isPlaying.value = false
            }
        }
    }

    actual override fun pause() {
        stopCurrent()
        lastStartMs = _currentTimeMs.value
        println("Desktop: Paused at $lastStartMs ms")
    }

    actual override fun seekTo(timeMs: Long) {
        stopCurrent()
        _currentTimeMs.value = timeMs
        lastStartMs = timeMs
        println("Desktop: Seeking to $timeMs (Seeking not fully supported in this JLayer shim)")
        // In a real player, we would resume from this point.
    }

    actual override fun release() {
        stopCurrent()
        scope.cancel()
        println("Desktop: Released")
    }

    private fun stopCurrent() {
        _isPlaying.value = false
        player?.close()
        player = null
        playbackJob?.cancel()
        playbackJob = null
    }
}
