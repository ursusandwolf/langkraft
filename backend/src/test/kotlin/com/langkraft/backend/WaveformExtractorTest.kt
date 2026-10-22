package com.langkraft.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class WaveformExtractorTest {
    @Test fun normalizesSignedSamplesAndDeletesTemporaryPcm() = runBlocking {
        lateinit var pcm: File
        val extractor = WaveformExtractor(ProcessRunner { arguments ->
            pcm = File(arguments.last())
            // -32768, +16384, silence: unsigned low bytes must not affect the sign.
            pcm.writeBytes(byteArrayOf(0, -128, 0, 64, 0, 0))
            ""
        })
        val result = extractor.extract(File("unused.mp3"), 3.0 / 4000)
        assertEquals(200, result.size)
        assertEquals(1f, result[0])
        assertEquals(0.5f, result[66])
        assertEquals(0f, result[133])
        assertFalse(pcm.exists())
    }

    @Test fun cancellationPropagatesAndDeletesPcm(): Unit = runBlocking {
        lateinit var pcm: File
        val extractor = WaveformExtractor(ProcessRunner { arguments ->
            pcm = File(arguments.last())
            throw CancellationException("stop ffmpeg")
        })
        assertFailsWith<CancellationException> { extractor.extract(File("unused.mp3"), 1.0) }
        assertFalse(pcm.exists())
    }
}
