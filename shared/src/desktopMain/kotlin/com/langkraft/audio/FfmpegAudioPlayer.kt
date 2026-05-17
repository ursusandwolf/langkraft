package com.langkraft.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.OutputStream

class FfmpegAudioPlayer : AudioPlayer {
    override val currentTimeMs = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)

    private var process: Process? = null
    private var processInput: OutputStream? = null
    private var currentUrl: String = ""
    private var startTimeMs: Long = 0L
    private var startSystemTime: Long = 0L
    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun load(url: String) {
        currentUrl = url
        startPlayback(0L)
    }

    private fun startPlayback(startMs: Long) {
        release()
        startTimeMs = startMs
        startSystemTime = System.currentTimeMillis()

        val seconds = startMs / 1000
        val builder = ProcessBuilder("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", "-ss", seconds.toString(), currentUrl)
        process = builder.start()
        processInput = process?.outputStream
        isPlaying.value = true

        timerJob = scope.launch {
            while (isActive && isPlaying.value) {
                currentTimeMs.value = startTimeMs + (System.currentTimeMillis() - startSystemTime)
                delay(100)
            }
        }
    }

    override fun play() {
        if (process != null) {
            Runtime.getRuntime().exec("kill -CONT ${process!!.pid()}")
            isPlaying.value = true
            startSystemTime = System.currentTimeMillis() // Reset start time for tracking
            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive && isPlaying.value) {
                    currentTimeMs.value = startTimeMs + (System.currentTimeMillis() - startSystemTime)
                    delay(100)
                }
            }
        }
    }

    override fun pause() {
        if (process != null) {
            Runtime.getRuntime().exec("kill -STOP ${process!!.pid()}")
            isPlaying.value = false
            timerJob?.cancel()
            // Store current time before pausing
            startTimeMs = currentTimeMs.value
        }
    }

    override fun seekTo(timeMs: Long) {
        startPlayback(timeMs)
    }

    override fun setPlaybackSpeed(speed: Double) {
        // Speed control requires filtergraph modification
    }

    override fun release() {
        timerJob?.cancel()
        if (process != null) {
            try {
                processInput?.write("q".toByteArray())
                processInput?.flush()
                processInput?.close()
                process?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
            } finally {
                process?.destroyForcibly()
                process = null
                processInput = null
            }
        }
        isPlaying.value = false
    }
}