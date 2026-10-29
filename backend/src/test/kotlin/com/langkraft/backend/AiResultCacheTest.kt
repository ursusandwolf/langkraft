package com.langkraft.backend

import com.langkraft.backend.ai.AiResultCache
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AiResultCacheTest {
    @Test fun identicalCallsShareGenerationAndCancellingAWaiterDoesNotCancelTheLeader() = runTest {
        val cache = AiResultCache<String, String>(10, 1024, 1000, { _, _ -> 1 })
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val leader = async { cache.getOrLoad("same") { calls++; release.await(); "value" } }
        runCurrent()
        val waiter = async { cache.getOrLoad("same") { error("Must share the leader") } }
        val cancelledWaiter = async { cache.getOrLoad("same") { error("Must share the leader") } }
        runCurrent()
        cancelledWaiter.cancelAndJoin()
        release.complete(Unit)
        assertEquals("value", leader.await())
        assertEquals("value", waiter.await())
        assertEquals(1, calls)
    }

    @Test fun failureAndCancelledLeaderDoNotPoisonRetry() = runTest {
        val cache = AiResultCache<String, String>(10, 1024, 1000, { _, _ -> 1 })
        assertFailsWith<IllegalStateException> { cache.getOrLoad("key") { error("provider failed") } }
        val leader = async { cache.getOrLoad("key") { awaitCancellation() } }
        runCurrent()
        val waiter = async { cache.getOrLoad("key") { error("Must wait") } }
        runCurrent()
        leader.cancelAndJoin()
        assertFailsWith<CancellationException> { waiter.await() }
        assertEquals("retry", cache.getOrLoad("key") { "retry" })
    }

    @Test fun ttlLruAndPayloadBudgetEvictEntriesAndOversizedValuesAreNotStored() = runTest {
        var time = 0L
        val cache = AiResultCache<String, String>(2, 6, 10, { _, value -> value.length.toLong() }, { time })
        var calls = 0
        suspend fun get(key: String, value: String = "123") = cache.getOrLoad(key) { calls++; value }
        get("a"); get("b"); get("a"); get("c")
        assertEquals(3, calls)
        get("b")
        assertEquals(4, calls, "Least recently used a/b entry must be evicted")
        time = 10
        get("b")
        assertEquals(5, calls, "Expired value must be regenerated")
        get("large", "1234567"); get("large", "1234567")
        assertEquals(7, calls, "Values over the payload budget must not be cached")
    }
}
