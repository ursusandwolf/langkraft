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
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL

class AudioPlayerImpl : AudioPlayer {
    private var audio = window.document.createElement("audio") as HTMLAudioElement
    private val _currentTimeMs = MutableStateFlow(0L)
    override val currentTimeMs: StateFlow<Long> = _currentTimeMs
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _error = MutableStateFlow<AudioPlayerError?>(null)
    override val error: StateFlow<AudioPlayerError?> = _error
    private val scope = CoroutineScope(Dispatchers.Main)
    private var timerJob: Job? = null
    private var loadJob: Job? = null
    private var ownedUrl: String? = null
    private var playWhenReady = false
    private var pendingSeek: Long? = null
    private var generation = 0
    private var released = false
    private var requestId = 0L
    private var sourceErrorListener: ((Event) -> Unit)? = null

    private fun bindEvents(element: HTMLAudioElement, sourceGeneration: Int, sourceRequest: Long) {
        fun isCurrent() = !released && generation == sourceGeneration
        element.onplay = { if (isCurrent()) { _isPlaying.value = true; startTimer() } }
        element.onpause = { if (isCurrent()) { _isPlaying.value = false; timerJob?.cancel() } }
        element.onended = {
            if (isCurrent()) {
                _isPlaying.value = false
                _currentTimeMs.value = (element.currentTime * 1000).toLong()
                timerJob?.cancel()
            }
        }
        element.onseeked = { if (isCurrent()) _currentTimeMs.value = (element.currentTime * 1000).toLong(); Unit }
        element.onloadedmetadata = { if (isCurrent()) applyPendingSeek(); Unit }
        sourceErrorListener = {
            if (isCurrent() && element.error != null) {
                timerJob?.cancel()
                _isPlaying.value = false
                _error.value = AudioPlayerError(sourceRequest, AudioErrorReason.SOURCE_ERROR, "Unable to load audio")
            }
        }
        element.addEventListener("error", requireNotNull(sourceErrorListener))
    }

    private fun discardSource() {
        sourceErrorListener?.let { audio.removeEventListener("error", it) }
        sourceErrorListener = null
        audio.onplay = null
        audio.onpause = null
        audio.onended = null
        audio.onseeked = null
        audio.onloadedmetadata = null
        audio.pause()
        audio.removeAttribute("src")
        audio.load()
        timerJob?.cancel()
        _isPlaying.value = false
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

    override fun load(url: String, requestId: Long) {
        check(!released) { "Audio player is released" }
        val sourceGeneration = ++generation
        this.requestId = requestId
        playWhenReady = false
        loadJob?.cancel()
        discardSource()
        revokeUrl()
        pendingSeek = null
        _error.value = null
        _currentTimeMs.value = 0
        // A fresh element keeps queued events from the previous source isolated.
        audio = window.document.createElement("audio") as HTMLAudioElement
        bindEvents(audio, sourceGeneration, requestId)
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
                    if (sourceGeneration == generation && !released) {
                        val reason = if (error.asDynamic().name == "LocalAudioUnavailableError")
                            AudioErrorReason.LOCAL_ASSET_UNAVAILABLE else AudioErrorReason.STORAGE_ERROR
                        _error.value = AudioPlayerError(requestId, reason, error.message ?: "Unable to read saved audio")
                    }
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
        val sourceRequest = requestId
        audio.play().catch { error ->
            if (request == generation && playWhenReady && !released) {
                timerJob?.cancel()
                _isPlaying.value = false
                val reason = if (error.asDynamic().name == "NotAllowedError")
                    AudioErrorReason.AUTOPLAY_BLOCKED else AudioErrorReason.SOURCE_ERROR
                _error.value = AudioPlayerError(sourceRequest, reason, error.message ?: "Unable to play audio")
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
        discardSource()
        revokeUrl()
        _isPlaying.value = false
    }
}
