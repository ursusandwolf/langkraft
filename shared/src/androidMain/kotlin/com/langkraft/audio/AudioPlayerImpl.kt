package com.langkraft.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.FileNotFoundException

class AudioPlayerImpl(context: Context) : AudioPlayer {
    private val exoPlayer = ExoPlayer.Builder(context).build()
    
    private val _currentTimeMs = MutableStateFlow(0L)
    override val currentTimeMs: StateFlow<Long> = _currentTimeMs
    
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _error = MutableStateFlow<AudioPlayerError?>(null)
    override val error: StateFlow<AudioPlayerError?> = _error
    private var errorListener: Player.Listener? = null
    private var generation = 0L

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _currentTimeMs.value = exoPlayer.currentPosition
            }

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

    override fun load(url: String, requestId: Long) {
        val sourceGeneration = ++generation
        errorListener?.let(exoPlayer::removeListener)
        errorListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (sourceGeneration != generation) return
                val missingLocal = !url.startsWith("http://", ignoreCase = true) &&
                    !url.startsWith("https://", ignoreCase = true) &&
                    generateSequence<Throwable>(error) { it.cause }.any { it is FileNotFoundException }
                _error.value = AudioPlayerError(requestId,
                    if (missingLocal) AudioErrorReason.LOCAL_ASSET_UNAVAILABLE else AudioErrorReason.SOURCE_ERROR,
                    error.message ?: "Unable to play audio")
            }
        }.also(exoPlayer::addListener)
        _error.value = null
        _currentTimeMs.value = 0
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    override fun play() {
        exoPlayer.play()
    }

    override fun pause() {
        exoPlayer.pause()
    }

    override fun seekTo(timeMs: Long) {
        exoPlayer.seekTo(timeMs)
        _currentTimeMs.value = exoPlayer.currentPosition
    }

    override fun setPlaybackSpeed(speed: Double) {
        exoPlayer.playbackParameters = androidx.media3.common.PlaybackParameters(speed.toFloat())
    }

    override fun release() {
        generation++
        errorListener?.let(exoPlayer::removeListener)
        errorListener = null
        stopTimer()
        scope.cancel()
        exoPlayer.release()
        _isPlaying.value = false
    }
}
