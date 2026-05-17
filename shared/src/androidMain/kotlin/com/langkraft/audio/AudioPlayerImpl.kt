package com.langkraft.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual class AudioPlayerImpl(context: Context) : AudioPlayer {
    private val exoPlayer = ExoPlayer.Builder(context).build()
    
    private val _currentTimeMs = MutableStateFlow(0L)
    actual override val currentTimeMs: StateFlow<Long> = _currentTimeMs
    
    private val _isPlaying = MutableStateFlow(false)
    actual override val isPlaying: StateFlow<Boolean> = _isPlaying

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) startTimer() else stopTimer()
            }
        })
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                _currentTimeMs.value = exoPlayer.currentPosition
                delay(100) // Update every 100ms for smooth UI sync
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
    }

    actual override fun load(url: String) {
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    actual override fun play() {
        exoPlayer.play()
    }

    actual override fun pause() {
        exoPlayer.pause()
    }

    actual override fun seekTo(timeMs: Long) {
        exoPlayer.seekTo(timeMs)
    }

    actual override fun setPlaybackSpeed(speed: Double) {
        exoPlayer.playbackParameters = PlaybackParameters(speed.toFloat())
    }

    actual override fun release() {
        stopTimer()
        exoPlayer.release()
    }
}
