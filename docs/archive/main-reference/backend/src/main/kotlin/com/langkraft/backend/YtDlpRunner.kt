package com.langkraft.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

fun interface YtDlpRunner {
    suspend fun execute(arguments: List<String>)
}

/** No shell is involved: each option and the URL is a separate process argument. */
class ProcessYtDlpRunner(
    private val executable: String = System.getenv("YT_DLP_EXECUTABLE") ?: "yt-dlp",
    private val timeoutSeconds: Long = 600,
    private val temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"))
) : YtDlpRunner {
    override suspend fun execute(arguments: List<String>) {
        val log = withContext(NonCancellable + Dispatchers.IO) {
            Files.createTempFile(temporaryDirectory, "langkraft-ytdlp-", ".log").toFile()
        }
        var process: Process? = null
        try {
            runInterruptible(Dispatchers.IO) {
                val child = ProcessBuilder(listOf(executable) + arguments)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
                process = child
                if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw IngestionException("YouTube download timed out")
                }
                if (child.exitValue() != 0) {
                    throw IngestionException("yt-dlp exited with code ${child.exitValue()}")
                }
            }
        } finally {
            // Cancellation interrupts runInterruptible's worker. Reap processes on
            // an un-interrupted worker before deleting files held open on Windows.
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { child ->
                    if (child.isAlive) {
                        val descendants = child.descendants().use { it.toList() }
                        descendants.forEach { it.destroyForcibly() }
                        child.destroyForcibly()
                        child.waitFor(5, TimeUnit.SECONDS)
                        descendants.forEach { descendant ->
                            runCatching { descendant.onExit().get(5, TimeUnit.SECONDS) }
                        }
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
