package com.langkraft.backend

import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessYtDlpRunnerTest {
    @get:Rule val directory = TemporaryFolder()
    private val java = File(System.getProperty("java.home"),
        "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").path

    private fun arguments(marker: String): List<String> {
        val fixture = Class.forName("com.langkraft.backend.ProcessFixture")
        return listOf("-cp", File(fixture.protectionDomain.codeSource.location.toURI()).path, fixture.name, marker)
    }

    private suspend fun awaitExit(pid: Long) = withTimeout(10_000) {
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(20)
    }

    @Test fun nonzeroExitIsReported() = runBlocking {
        val failure = assertFailsWith<IngestionException> {
            ProcessYtDlpRunner(java).execute(arguments("exit"))
        }
        assertTrue(failure.message.orEmpty().contains("7"))
    }

    @Test fun timeoutTerminatesTheRealChildProcess() = runBlocking {
        val marker = File(directory.root, "timeout.pid")
        val failure = assertFailsWith<IngestionException> {
            ProcessYtDlpRunner(java, timeoutSeconds = 2, temporaryDirectory = directory.root.toPath())
                .execute(arguments(marker.path))
        }
        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertTrue(marker.isFile, "The fixture must have started before the timeout")
        awaitExit(marker.readText().toLong())
        assertTrue(directory.root.listFiles().orEmpty().none { it.extension == "log" })
    }

    @Test fun cancellationTerminatesTheRealChildProcess() = runBlocking {
        val marker = File(directory.root, "cancel.pid")
        val job = launch(Dispatchers.Default) {
            ProcessYtDlpRunner(java, temporaryDirectory = directory.root.toPath()).execute(arguments(marker.path))
        }
        try {
            withTimeout(10_000) { while (!marker.isFile || marker.length() == 0L) delay(20) }
            job.cancelAndJoin()
            awaitExit(marker.readText().toLong())
            assertTrue(directory.root.listFiles().orEmpty().none { it.extension == "log" })
        } finally { job.cancelAndJoin() }
    }
}
