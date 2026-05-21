package com.langkraft.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * A cross-platform AudioPlayer implementation using ffplay.
 * It restarts the process on play/seek to ensure consistency across OSs 
 * without relying on platform-specific signals like SIGSTOP/SIGCONT.
 */
class FfmpegAudioPlayer : AudioPlayer {
    private val _currentTimeMs = MutableStateFlow(0L)
    override val currentTimeMs: StateFlow<Long> = _currentTimeMs.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var process: Process? = null
    private var currentUrl: String = ""
    private var lastStartSystemTime: Long = 0L
    private var timeAtLastStartMs: Long = 0L
    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // Ensure process is killed when JVM exits
        Runtime.getRuntime().addShutdownHook(Thread {
            stopProcess()
        })
    }

    override fun load(url: String) {
        currentUrl = url
        _currentTimeMs.value = 0
        timeAtLastStartMs = 0
        if (_isPlaying.value) {
            startProcess(0)
        }
    }

    override fun play() {
        if (_isPlaying.value) return
        startProcess(_currentTimeMs.value)
    }

    override fun pause() {
        if (!_isPlaying.value) return
        stopProcess()
    }

    override fun seekTo(timeMs: Long) {
        _currentTimeMs.value = timeMs
        timeAtLastStartMs = timeMs
        if (_isPlaying.value) {
            startProcess(timeMs)
        }
    }

    override fun setPlaybackSpeed(speed: Double) {
        // Implementation for speed would require complex ffplay filters (-af atempo)
        // and process restart. Not implemented for simplicity in this TUI version.
    }

    private fun startProcess(startMs: Long) {
        stopProcess()
        
        val seconds = startMs / 1000.0
        val builder = ProcessBuilder(
            "ffplay", 
            "-nodisp", 
            "-autoexit", 
            "-loglevel", "quiet", 
            "-ss", seconds.toString(), 
            currentUrl
        )
        
        try {
            process = builder.start()
            lastStartSystemTime = System.currentTimeMillis()
            timeAtLastStartMs = startMs
            _isPlaying.value = true
            
            startTimer()
        } catch (e: Exception) {
            _isPlaying.value = false
            // Optional: notify error via flow
        }
    }

    private fun stopProcess() {
        timerJob?.cancel()
        process?.destroy()
        process = null
        _isPlaying.value = false
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && _isPlaying.value) {
                val elapsed = System.currentTimeMillis() - lastStartSystemTime
                _currentTimeMs.value = timeAtLastStartMs + elapsed
                
                // Check if process is still alive
                if (process?.isAlive == false) {
                    _isPlaying.value = false
                    break
                }
                delay(100)
            }
        }
    }

    override fun release() {
        stopProcess()
        scope.cancel()
    }
}
