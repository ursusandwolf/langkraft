package com.langkraft.backend

import com.langkraft.backend.db.*
import com.langkraft.domain.model.*
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackendWorkTest {
    @TempDir lateinit var directory: Path

    @Test fun passwordOperationsShareTheConfiguredConcurrencyLimit(): Unit = runBlocking {
        val work = BackendWork(passwordParallelism = 2)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        val jobs = (1..8).map {
            async(Dispatchers.Default) {
                work.password {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, count) }
                    started.incrementAndGet()
                    entered.countDown()
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)) }
                    finally { active.decrementAndGet() }
                }
            }
        }
        try {
            await(entered)
            assertEquals(2, active.get())
            assertEquals(2, started.get())
        } finally { release.countDown() }
        jobs.awaitAll()
        assertEquals(8, started.get())
        assertEquals(2, peak.get())
    }

    @Test fun blockedPasswordWorkDoesNotOccupyCallerOrJdbcDispatcher(): Unit = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "next2-http-test") }.asCoroutineDispatcher().use { caller ->
            Executors.newSingleThreadExecutor { Thread(it, "next2-jdbc-test") }.asCoroutineDispatcher().use { jdbc ->
                val work = BackendWork(1, jdbc)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val hashing = async(caller) {
                    work.password {
                        assertFalse(Thread.currentThread().name.startsWith("next2-"))
                        entered.countDown()
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                    }
                }
                try {
                    await(entered)
                    withTimeout(5_000) {
                        assertTrue(withContext(caller) { Thread.currentThread().name.startsWith("next2-http-test") })
                        assertTrue(withContext(caller) { work.jdbc { Thread.currentThread().name.startsWith("next2-jdbc-test") } })
                    }
                } finally { release.countDown() }
                hashing.await()
            }
        }
    }

    @Test fun cancellationDoesNotReleaseAnExecutingPasswordSlotOrRunQueuedWork(): Unit = runBlocking {
        val work = BackendWork(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val running = async { work.password {
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
        } }
        var queuedRan = false
        try {
            await(entered)
            val queued = async(start = CoroutineStart.UNDISPATCHED) { work.password { queuedRan = true } }
            running.cancel()
            queued.cancel()
            // A running synchronous BCrypt call retains its thread/slot until it actually finishes.
            assertFalse(running.isCompleted)
            release.countDown()
            assertFailsWith<CancellationException> { running.await() }
            assertFailsWith<CancellationException> { queued.await() }
            assertFalse(queuedRan)
            assertEquals("available", work.password { "available" })
        } finally { release.countDown() }
    }

    @Test fun cancelledRegistrationWaitingForPasswordWorkDoesNotCreateUser(): Unit = runTest {
        val work = BackendWork(1, StandardTestDispatcher(testScheduler))
        val users = BackendUserRepository(BackendDatabase.open(directory.resolve("auth.db").toFile()))
        val service = AuthService(users, jwt(), work)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val occupying = async { work.password {
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
        } }
        try {
            runCurrent()
            await(entered)
            val registration = async { service.register(RegisterRequest("a@example.org", "password", "A")) }
            runCurrent() // Credential validation and the JDBC lookup finish; password work is queued.
            registration.cancel()
            release.countDown()
            occupying.await()
            assertFailsWith<CancellationException> { registration.await() }
            assertNull(users.findByEmail("a@example.org"))
        } finally { release.countDown() }
    }

    @Test fun concurrentRegistrationKeepsOneAccountAndLoginStillVerifiesPassword(): Unit = runBlocking {
        val users = BackendUserRepository(BackendDatabase.open(directory.resolve("race.db").toFile()))
        val service = AuthService(users, jwt(), BackendWork(2))
        val request = RegisterRequest("a@example.org", "password", "A")
        val results = (1..2).map { async { service.register(request) } }.awaitAll()
        val created = results.filterNotNull().single()
        assertEquals(created.user.id, users.findByEmail(request.email)?.id)
        assertEquals(created.user.id, service.login(AuthRequest(request.email, request.password))?.user?.id)
        assertNull(service.login(AuthRequest(request.email, "wrong")))
    }

    @Test fun syncUsesJdbcDispatcherAndRollsBackTheWholeBatchOnOwnershipFailure(): Unit = runBlocking {
        val db = BackendDatabase.open(directory.resolve("sync.db").toFile())
        val users = BackendUserRepository(db)
        val alice = users.createUser("a@example.org", "hash", "A")!!
        val bob = users.createUser("b@example.org", "hash", "B")!!
        val repository = BackendVocabularyRepository(db)
        val existing = SyncEntry(VocabularyWord("owned", "Haus", contextSentence = "Das Haus", lastUpdated = 1), "UPSERT")
        repository.synchronize(bob, listOf(existing), 0)
        Executors.newSingleThreadExecutor { Thread(it, "next2-jdbc-sync") }.asCoroutineDispatcher().use { jdbc ->
            var called = false
            val observed = object : VocabularySyncRepository by repository {
                override fun synchronize(userId: String, clientChanges: List<SyncEntry>, lastSyncTimestamp: Long): SyncResponse {
                    assertTrue(Thread.currentThread().name.startsWith("next2-jdbc-sync"))
                    called = true
                    return repository.synchronize(userId, clientChanges, lastSyncTimestamp)
                }
            }
            val service = SyncService(users, observed, BackendWork(1, jdbc))
            val newWord = existing.copy(word = existing.word.copy(id = "new"))
            assertFailsWith<RequestValidationException> { service.performSync(alice, SyncRequest(0, listOf(newWord, existing))) }
            assertTrue(called)
            assertTrue(repository.synchronize(alice, emptyList(), 0).serverChanges.isEmpty())
            assertEquals("owned", repository.synchronize(bob, emptyList(), 0).serverChanges.single().word.id)
        }
    }

    @Test fun cancellationOfStartedSyncRetainsAtomicCommitAndPropagatesToCaller(): Unit = runBlocking {
        val db = BackendDatabase.open(directory.resolve("cancel-sync.db").toFile())
        val users = BackendUserRepository(db)
        val userId = users.createUser("a@example.org", "hash", "A")!!
        val repository = BackendVocabularyRepository(db)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = object : VocabularySyncRepository by repository {
            override fun synchronize(userId: String, clientChanges: List<SyncEntry>, lastSyncTimestamp: Long): SyncResponse {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                return repository.synchronize(userId, clientChanges, lastSyncTimestamp)
            }
        }
        val service = SyncService(users, paused, BackendWork())
        val changes = (1..2).map { SyncEntry(VocabularyWord("w$it", "Haus", contextSentence = "Das Haus", lastUpdated = 1), "UPSERT") }
        val syncing = async { service.performSync(userId, SyncRequest(0, changes)) }
        try {
            await(entered)
            syncing.cancel()
            release.countDown()
            assertFailsWith<CancellationException> { syncing.await() }
            assertEquals(2, repository.synchronize(userId, emptyList(), 0).serverChanges.size)
            service.performSync(userId, SyncRequest(0, changes))
            assertEquals(2, repository.synchronize(userId, emptyList(), 0).serverChanges.size)
        } finally { release.countDown() }
    }

    @Test fun invalidPasswordConcurrencyFailsAtBootstrap() {
        for (value in listOf("0", "17", "-1", "invalid")) {
            assertFails { BackendWork.from(MapApplicationConfig("auth.max_concurrent_hashes" to value)) }
        }
        BackendWork.from(MapApplicationConfig())
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Operation did not reach the expected boundary")
    }

    private fun jwt() = JwtService(MapApplicationConfig(
        "jwt.secret" to "test-secret-with-at-least-thirty-two-characters",
        "jwt.issuer" to "tests", "jwt.audience" to "tests", "jwt.validity_ms" to "86400000"
    ))
}
