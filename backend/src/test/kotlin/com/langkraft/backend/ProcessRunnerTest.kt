package com.langkraft.backend

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ProcessRunnerTest {
    @TempDir lateinit var directory: Path
    private val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").path
    private fun arguments(marker: String): List<String> {
        val fixture = Class.forName("com.langkraft.backend.ProcessFixture")
        return listOf("-cp", File(fixture.protectionDomain.codeSource.location.toURI()).path, fixture.name, marker)
    }
    private suspend fun awaitExit(pid: Long) = withTimeout(10_000) {
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20)
    }

    @Test fun nonzeroExitIsReported() = runBlocking {
        val failure = assertFailsWith<IngestionException> { ExecutableProcessRunner(java).execute(arguments("exit")) }
        assertTrue(failure.message.orEmpty().contains("7"))
    }

    @Test fun timeoutTerminatesRealProcess() = runBlocking {
        val marker = directory.resolve("timeout with spaces.pid").toFile()
        val failure = assertFailsWith<IngestionException> {
            ExecutableProcessRunner(java, 2, directory).execute(arguments(marker.path))
        }
        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertTrue(marker.isFile)
        awaitExit(marker.readText().toLong())
        assertTrue(directory.toFile().listFiles().orEmpty().none { it.extension == "log" })
    }

    @Test fun cancellationTerminatesRealProcess() = runBlocking {
        val marker = directory.resolve("cancel.pid").toFile()
        val job = launch(Dispatchers.Default) { ExecutableProcessRunner(java, temporaryDirectory = directory).execute(arguments(marker.path)) }
        try {
            withTimeout(10_000) { while (!marker.isFile || marker.length() == 0L) delay(20) }
            job.cancelAndJoin()
            awaitExit(marker.readText().toLong())
            assertTrue(directory.toFile().listFiles().orEmpty().none { it.extension == "log" })
        } finally { job.cancelAndJoin() }
    }

    @Test fun cancellationTerminatesDescendants() = runBlocking {
        val parent = directory.resolve("parent.pid").toFile()
        val child = directory.resolve("child.pid").toFile()
        val job = launch(Dispatchers.Default) {
            ExecutableProcessRunner(java, temporaryDirectory = directory)
                .execute(arguments("spawn") + listOf(parent.path, child.path))
        }
        try {
            withTimeout(10_000) { while (!child.isFile || child.length() == 0L || !parent.isFile || parent.length() == 0L) delay(20) }
            job.cancelAndJoin()
            awaitExit(parent.readText().toLong())
            awaitExit(child.readText().toLong())
        } finally { job.cancelAndJoin() }
    }

    @Test fun quotaViolationStopsRealProcessTreeAndLeavesNoLogs() = runBlocking {
        val storage = BackendMediaStorage(directory.resolve("downloads").toString(), limits = MediaLimits(1024, 2048))
        val id = UUID.randomUUID().toString()
        val lease = storage.beginImport(id)
        val jobDirectory = storage.importDirectory(id)
        val parent = jobDirectory.resolve("parent.pid").toFile()
        val child = jobDirectory.resolve("child.pid").toFile()
        val partial = jobDirectory.resolve("source.part")
        try {
            withTimeout(15_000) {
                assertFailsWith<MediaLimitException> {
                    ExecutableProcessRunner(java, 30).executeIn(
                        arguments("quota-tree") + listOf(partial.toString(), parent.path, child.path), jobDirectory
                    ) { storage.checkImportBudget(id) }
                }
            }
            assertTrue(parent.isFile && child.isFile)
            awaitExit(parent.readText().toLong())
            awaitExit(child.readText().toLong())
            assertTrue(jobDirectory.toFile().listFiles().orEmpty().none { it.extension == "log" })
            storage.discardImport(id)
            assertFalse(jobDirectory.toFile().exists())
        } finally {
            lease.close()
            // Avoid leaving helpers behind even if an assertion fails.
            listOf(parent, child).filter { it.isFile }.forEach { marker ->
                marker.readText().toLongOrNull()?.let { ProcessHandle.of(it).ifPresent { process -> process.destroyForcibly() } }
            }
        }
    }

    @Test fun trackedDescendantIsStoppedWhenParentExitsBeforeCleanup() = runBlocking {
        val parent = directory.resolve("exited-parent.pid").toFile()
        val child = directory.resolve("orphan-child.pid").toFile()
        try {
            ExecutableProcessRunner(java, temporaryDirectory = directory)
                .execute(arguments("spawn-and-exit") + listOf(parent.path, child.path))
            assertTrue(child.isFile)
            awaitExit(parent.readText().toLong())
            awaitExit(child.readText().toLong())
        } finally {
            listOf(parent, child).filter { it.isFile }.forEach { marker ->
                marker.readText().toLongOrNull()?.let { ProcessHandle.of(it).ifPresent { process -> process.destroyForcibly() } }
            }
        }
    }

    @Test fun processOutputLimitIsEnforcedBeforeProcessExit() = runBlocking {
        val marker = directory.resolve("log-overflow.pid").toFile()
        try {
            val failure = withTimeout(10_000) {
                assertFailsWith<IngestionException> {
                    ExecutableProcessRunner(java, 30, directory).execute(arguments("log-overflow") + marker.path)
                }
            }
            assertContains(failure.message.orEmpty(), "output is too large")
            awaitExit(marker.readText().toLong())
            assertTrue(directory.toFile().listFiles().orEmpty().none { it.extension == "log" })
        } finally {
            marker.takeIf { it.isFile }?.readText()?.toLongOrNull()?.let {
                ProcessHandle.of(it).ifPresent { process -> process.destroyForcibly() }
            }
        }
    }
}
