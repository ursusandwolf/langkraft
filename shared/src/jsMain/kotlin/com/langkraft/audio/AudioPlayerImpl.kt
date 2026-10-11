package com.langkraft.audio

import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import org.w3c.dom.HTMLAudioElement

class AudioPlayerImpl : AudioPlayer {
    private val audio = document.createElement("audio") as HTMLAudioElement
    override val currentTimeMs = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)
    override val error = MutableStateFlow<String?>(null)
    private var released = false

    init {
        audio.ontimeupdate = { currentTimeMs.value = (audio.currentTime * 1000).toLong(); Unit }
        audio.onseeked = { currentTimeMs.value = (audio.currentTime * 1000).toLong(); Unit }
        audio.onplay = { isPlaying.value = true; Unit }
        audio.onpause = { isPlaying.value = false; Unit }
        audio.onended = { isPlaying.value = false; Unit }
        audio.onerror = { error.value = "Audio playback failed"; Unit }
    }
    override fun load(url: String) {
        error.value = null
        currentTimeMs.value = 0
        audio.src = url
        audio.load()
    }
    override fun play() {
        audio.play().catch { failure ->
            if (!released) error.value = failure.message ?: "Browser blocked audio playback"
            Unit
        }
    }
    override fun pause() { audio.pause() }
    override fun seekTo(timeMs: Long) {
        audio.currentTime = timeMs.coerceAtLeast(0) / 1000.0
        currentTimeMs.value = timeMs.coerceAtLeast(0)
    }
    override fun release() {
        if (released) return
        released = true
        audio.pause()
        audio.ontimeupdate = null
        audio.onseeked = null
        audio.onplay = null
        audio.onpause = null
        audio.onended = null
        audio.onerror = null
        audio.removeAttribute("src")
        audio.load()
    }
}