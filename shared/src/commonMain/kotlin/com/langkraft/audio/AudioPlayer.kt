package com.langkraft.audio

import kotlinx.coroutines.flow.StateFlow

interface AudioPlayer {
    val currentTimeMs: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val error: StateFlow<String?>
    
    fun load(url: String)
    fun play()
    fun pause()
    fun seekTo(timeMs: Long)
    fun release()
}
