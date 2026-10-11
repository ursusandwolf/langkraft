package com.langkraft.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class AudioPlayerImpl(context: Context) : AudioPlayer {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    override val currentTimeMs = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)
    override val error = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var released = false

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying.value = playing }
            override fun onPlayerError(failure: PlaybackException) { error.value = failure.message ?: "Audio playback failed" }
        })
        scope.launch {
            while (isActive) {
                currentTimeMs.value = player.currentPosition
                delay(100)
            }
        }
    }
    override fun load(url: String) {
        error.value = null
        currentTimeMs.value = 0
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }
    override fun play() { player.play() }
    override fun pause() { player.pause() }
    override fun seekTo(timeMs: Long) {
        player.seekTo(timeMs.coerceAtLeast(0))
        currentTimeMs.value = player.currentPosition
    }
    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        player.release()
    }
}