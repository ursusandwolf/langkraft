package com.langkraft.audio

import com.langkraft.data.storage.readAudioBlob
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.url.URL

class AudioPlayerImpl : AudioPlayer {
    private val audio = window.document.createElement("audio") as HTMLAudioElement
    private val _currentTimeMs = MutableStateFlow(0L)
    override val currentTimeMs: StateFlow<Long> = _currentTimeMs
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error
    private val scope = CoroutineScope(Dispatchers.Main)
    private var timerJob: Job? = null
    private var loadJob: Job? = null
    private var ownedUrl: String? = null
    private var playWhenReady = false
    private var pendingSeek: Long? = null
    private var generation = 0
    private var released = false

    init {
        audio.onplay = { _isPlaying.value = true; startTimer() }
        audio.onpause = { _isPlaying.value = false; timerJob?.cancel() }
        audio.onended = {
            _isPlaying.value = false
            _currentTimeMs.value = (audio.currentTime * 1000).toLong()
            timerJob?.cancel()
        }
        audio.onseeked = { _currentTimeMs.value = (audio.currentTime * 1000).toLong(); Unit }
        audio.onloadedmetadata = { applyPendingSeek(); Unit }
        audio.addEventListener("error", {
            if (!released) _error.value = "Unable to load audio"
        })
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                _currentTimeMs.value = (audio.currentTime * 1000).toLong()
                delay(100)
            }
        }
    }

    override fun load(url: String) {
        check(!released) { "Audio player is released" }
        generation++
        pause()
        loadJob?.cancel()
        revokeUrl()
        pendingSeek = null
        _error.value = null
        _currentTimeMs.value = 0
        // Stop the previous source while IndexedDB loads the next track.
        audio.removeAttribute("src")
        audio.load()
        if (url.startsWith("indexeddb-audio:")) {
            loadJob = scope.launch {
                try {
                    val blob = readAudioBlob(url).await()
                    ensureActive()
                    ownedUrl = URL.createObjectURL(blob)
                    setSource(requireNotNull(ownedUrl))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _error.value = error.message ?: "Unable to read saved audio"
                }
            }
        } else {
            loadJob = null
            setSource(url)
        }
    }

    private fun setSource(url: String) {
        audio.src = url
        audio.load()
        if (playWhenReady) playNow()
    }

    override fun play() {
        check(!released) { "Audio player is released" }
        playWhenReady = true
        if (loadJob?.isActive != true) playNow()
    }

    private fun playNow() {
        val request = generation
        audio.play().catch { error ->
            if (request == generation && playWhenReady && !released) {
                _error.value = error.message ?: "Unable to play audio"
            }
        }
    }

    override fun pause() {
        playWhenReady = false
        audio.pause()
    }

    override fun seekTo(timeMs: Long) {
        pendingSeek = timeMs.coerceAtLeast(0)
        applyPendingSeek()
    }

    private fun applyPendingSeek() {
        val time = pendingSeek ?: return
        if (audio.readyState >= 1) {
            audio.currentTime = time / 1000.0
            _currentTimeMs.value = (audio.currentTime * 1000).toLong()
            pendingSeek = null
        }
    }

    private fun revokeUrl() {
        ownedUrl?.let(URL::revokeObjectURL)
        ownedUrl = null
    }

    override fun release() {
        released = true
        scope.cancel()
        audio.pause()
        audio.removeAttribute("src")
        audio.load()
        revokeUrl()
        _isPlaying.value = false
    }
}
