package com.langkraft.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

fun interface ProcessRunner {
    suspend fun execute(arguments: List<String>): String

    suspend fun executeIn(arguments: List<String>, directory: Path, checkBudget: () -> Unit = {}): String {
        val result = execute(arguments)
        checkBudget()
        return result
    }
}

/** No shell is involved: each option and the URL is a separate process argument. */
class ExecutableProcessRunner(
    private val executable: String = System.getenv("YT_DLP_EXECUTABLE") ?: "yt-dlp",
    private val timeoutSeconds: Long = 600,
    private val temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"))
) : ProcessRunner {
    init { require(timeoutSeconds > 0) }

    override suspend fun execute(arguments: List<String>): String = runProcess(arguments, temporaryDirectory) {}

    override suspend fun executeIn(arguments: List<String>, directory: Path, checkBudget: () -> Unit): String =
        runProcess(arguments, directory, checkBudget)

    private suspend fun runProcess(arguments: List<String>, directory: Path, checkBudget: () -> Unit): String {
        val log = withContext(NonCancellable + Dispatchers.IO) {
            Files.createTempFile(directory, "langkraft-process-", ".log").toFile()
        }
        var process: Process? = null
        val descendants = mutableSetOf<ProcessHandle>()
        try {
            return runInterruptible(Dispatchers.IO) {
                val child = ProcessBuilder(listOf(executable) + arguments)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
                process = child
                val started = System.nanoTime()
                while (true) {
                    child.descendants().use { descendants.addAll(it.toList()) }
                    val exited = child.waitFor(100, TimeUnit.MILLISECONDS)
                    child.descendants().use { descendants.addAll(it.toList()) }
                    if (exited) break
                    if (log.length() > 2 * 1024 * 1024) throw IngestionException("Media process output is too large")
                    checkBudget()
                    if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(timeoutSeconds)) {
                        throw IngestionException("Media process timed out")
                    }
                }
                checkBudget()
                if (child.exitValue() != 0) {
                    throw IngestionException("Media process exited with code ${child.exitValue()}")
                }
                if (log.length() > 2 * 1024 * 1024) throw IngestionException("Media process output is too large")
                log.readText()
            }
        } finally {
            // Cancellation interrupts runInterruptible's worker. Reap processes on
            // an un-interrupted worker before deleting files held open on Windows.
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { child ->
                    child.descendants().use { descendants.addAll(it.toList()) }
                    // Keep known descendants even if their parent exited before this finally block.
                    descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
                    if (child.isAlive) {
                        child.destroyForcibly()
                        child.waitFor(5, TimeUnit.SECONDS)
                    }
                    descendants.forEach { descendant ->
                        runCatching { descendant.onExit().get(5, TimeUnit.SECONDS) }
                    }
                }
                // Windows may briefly retain redirected file handles after exit.
                for (attempt in 0 until 40) {
                    if (log.delete() || !log.exists()) break
                    delay(50)
                }
            }
        }
    }
}
