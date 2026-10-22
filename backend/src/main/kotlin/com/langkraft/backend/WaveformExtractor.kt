package com.langkraft.backend

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/** PCM goes to a temporary file; only 200 amplitude buckets are held in memory. */
class WaveformExtractor(
    private val runner: ProcessRunner = ExecutableProcessRunner(System.getenv("FFMPEG_EXECUTABLE") ?: "ffmpeg", 120)
) {
    suspend fun extract(audio: File, durationSeconds: Double): List<Float> = withContext(Dispatchers.IO) {
        val pcm = Files.createTempFile("langkraft-waveform-", ".pcm").toFile()
        try {
            runner.execute(listOf("-nostdin", "-v", "error", "-y", "-i", audio.absolutePath,
                "-t", "14400", "-ac", "1", "-ar", "4000", "-f", "s16le", pcm.absolutePath))
            val buckets = IntArray(200)
            val totalSamples = (durationSeconds * 4000).toLong().coerceAtLeast(1)
            pcm.inputStream().buffered().use { input ->
                var sampleIndex = 0L
                while (true) {
                    ensureActive()
                    val low = input.read()
                    if (low == -1) break
                    val high = input.read()
                    if (high == -1) throw IngestionException("Incomplete waveform sample")
                    val sample = abs(((high shl 8) or low).toShort().toInt())
                    val bucket = (sampleIndex * 200 / totalSamples).coerceAtMost(199).toInt()
                    buckets[bucket] = maxOf(buckets[bucket], sample)
                    sampleIndex++
                }
            }
            val peak = buckets.maxOrNull() ?: 0
            buckets.map { if (peak == 0) 0f else it.toFloat() / peak }
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) {
            LoggerFactory.getLogger(WaveformExtractor::class.java).warn("Waveform extraction failed", exception)
            List(200) { 0f }
        } finally { pcm.delete() }
    }
}
