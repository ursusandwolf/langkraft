package com.langkraft.audio

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.OutputStream

class FfmpegAudioPlayer : AudioPlayer {
    override val currentTimeMs = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)
    
    private var process: Process? = null
    private var processInput: OutputStream? = null

    override fun load(url: String) {
        // Force cleanup of any lingering ffplay instances
        try {
            Runtime.getRuntime().exec("pkill ffplay").waitFor()
        } catch (e: Exception) {
            // Ignored
        }
        
        release()
        
        // Start ffplay in background
        val builder = ProcessBuilder("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", url)
        process = builder.start()
        processInput = process?.outputStream
        isPlaying.value = true
    }

    override fun play() {
        if (process != null) {
            // Send SIGCONT to resume
            Runtime.getRuntime().exec("kill -CONT ${process!!.pid()}")
            isPlaying.value = true
        }
    }

    override fun pause() {
        if (process != null) {
            // Send SIGSTOP to pause
            Runtime.getRuntime().exec("kill -STOP ${process!!.pid()}")
            isPlaying.value = false
        }
    }

    override fun seekTo(timeMs: Long) {
        // ffplay seeking via stdin is limited. 
        // For TUI, we can skip seeking for now or implement complex key-press sequences.
    }

    override fun setPlaybackSpeed(speed: Double) {
        // Speed control requires filtergraph modification which is complex via stdin
    }

    override fun release() {
        if (process != null) {
            try {
                processInput?.write("q".toByteArray())
                processInput?.flush()
                processInput?.close()
                process?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Ignore
            } finally {
                process?.destroyForcibly()
                process = null
                processInput = null
            }
        }
        isPlaying.value = false
    }
}