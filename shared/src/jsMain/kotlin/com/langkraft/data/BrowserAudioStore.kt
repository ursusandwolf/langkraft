package com.langkraft.data

import kotlinx.coroutines.await
import kotlin.js.Promise

private fun cacheAudio(key: String, url: String): Promise<String> = js("""
    (async function(key, url) {
        const cache = await caches.open('langkraft-audio-v1');
        const response = await fetch(url);
        if (!response.ok) throw new Error('Audio download failed');
        await cache.put(key, response);
        return key;
    })(key, url)
""")
private fun resolveAudio(key: String): Promise<String> = js("""
    (async function(key) {
        const cache = await caches.open('langkraft-audio-v1');
        const response = await cache.match(key);
        if (!response) throw new Error('Offline audio is unavailable. Import the video again.');
        return URL.createObjectURL(await response.blob());
    })(key)
""")
private fun revokeAudio(url: String) { js("URL.revokeObjectURL(url)") }

class BrowserAudioStore : AudioStore {
    private val objectUrls = mutableListOf<String>()
    override suspend fun download(contentId: String, audioUrl: String): String {
        require(Regex("[a-zA-Z0-9-]+").matches(contentId)) { "Invalid content ID" }
        return cacheAudio("/offline/audio/$contentId.opus", audioUrl).await()
    }
    override suspend fun resolve(path: String): String {
        if (!path.startsWith("/offline/audio/")) return path
        objectUrls.forEach(::revokeAudio)
        objectUrls.clear()
        val url = resolveAudio(path).await()
        objectUrls += url
        return url
    }
    override fun close() { objectUrls.forEach(::revokeAudio); objectUrls.clear() }
}