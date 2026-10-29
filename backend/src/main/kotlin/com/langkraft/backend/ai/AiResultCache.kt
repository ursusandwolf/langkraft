package com.langkraft.backend.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/** Successful results only; waiters share one call without holding a lock during IO. */
internal class AiResultCache<K, V>(
    private val maxEntries: Int,
    private val maxPayloadBytes: Long,
    private val ttlMs: Long,
    private val weight: (K, V) -> Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init { require(maxEntries > 0 && maxPayloadBytes > 0 && ttlMs > 0) }
    private data class Entry<V>(val value: V, val storedAt: Long, val bytes: Long)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val pending = mutableMapOf<K, CompletableDeferred<V>>()
    private var payloadBytes = 0L

    suspend fun getOrLoad(key: K, load: suspend () -> V): V {
        var leader = false
        val result = mutex.withLock {
            val entry = entries[key]
            if (entry != null && now() - entry.storedAt < ttlMs) return entry.value
            if (entry != null) { entries.remove(key); payloadBytes -= entry.bytes }
            pending[key] ?: CompletableDeferred<V>().also { pending[key] = it; leader = true }
        }
        if (!leader) return result.await()
        try {
            val value = load()
            val bytes = weight(key, value).coerceAtLeast(1)
            mutex.withLock {
                if (bytes <= maxPayloadBytes) {
                    entries[key] = Entry(value, now(), bytes)
                    payloadBytes += bytes
                    while (entries.size > maxEntries || payloadBytes > maxPayloadBytes) {
                        val oldest = entries.entries.iterator()
                        payloadBytes -= oldest.next().value.bytes
                        oldest.remove()
                    }
                }
                pending.remove(key)
                result.complete(value)
            }
            return value
        } catch (failure: Throwable) {
            withContext(NonCancellable) { mutex.withLock {
                pending.remove(key)
                result.completeExceptionally(failure)
            } }
            throw failure
        }
    }
}
