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
    private val _error = MutableStateFlow<AudioPlayerError?>(null)
    override val error: StateFlow<AudioPlayerError?> = _error.asStateFlow()
    private var requestId = 0L
    private var playbackSpeed = 1.0
    private val shutdownHook = Thread { stopProcess() }
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
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override fun load(url: String, requestId: Long) {
        this.requestId = requestId
        _error.value = null
        stopProcess()
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
        require(speed in 0.5..2.0)
        playbackSpeed = speed
        if (_isPlaying.value) startProcess(_currentTimeMs.value)
    }

    private fun startProcess(startMs: Long) {
        stopProcess()
        
        val seconds = startMs / 1000.0
        val builder = ProcessBuilder(
            System.getenv("FFPLAY_EXECUTABLE") ?: "ffplay",
            "-nodisp", 
            "-autoexit", 
            "-loglevel", "quiet", 
            "-ss", seconds.toString(), 
            "-af", "atempo=$playbackSpeed",
            currentUrl
        )
        
        try {
            _error.value = null
            process = builder.start()
            lastStartSystemTime = System.currentTimeMillis()
            timeAtLastStartMs = startMs
            _isPlaying.value = true
            
            startTimer()
        } catch (e: Exception) {
            _isPlaying.value = false
            _error.value = AudioPlayerError(requestId, AudioErrorReason.SOURCE_ERROR, "Cannot start ffplay: ${e.message}")
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
                _currentTimeMs.value = timeAtLastStartMs + (elapsed * playbackSpeed).toLong()
                
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
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}
