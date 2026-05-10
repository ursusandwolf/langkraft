package com.langkraft.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SrtParserTest {

    @Test
    fun `test parse srt`() {
        val srt = """
            1
            00:00:20,000 --> 00:00:24,400
            Hallo, wie geht es dir?
            
            2
            00:00:24,500 --> 00:00:28,000
            Mir geht es gut, danke.
        """.trimIndent()
        
        val lines = SrtParser.parse("test-id", srt)
        assertEquals(2, lines.size)
        assertEquals("Hallo, wie geht es dir?", lines[0].textDe)
        assertEquals(20000L, lines[0].startMs)
        assertEquals(24400L, lines[0].endMs)
    }

    @Test
    fun `test parse vtt`() {
        val vtt = """
            WEBVTT
            
            00:00:20.000 --> 00:00:24.400
            Hallo, wie geht es dir?
            
            00:00:24.500 --> 00:00:28.000
            Mir geht es gut, danke.
        """.trimIndent()
        
        val lines = SrtParser.parse("test-id", vtt)
        assertEquals(2, lines.size)
        assertEquals("Hallo, wie geht es dir?", lines[0].textDe)
        assertEquals(20000L, lines[0].startMs)
    }

    @Test
    fun `test parse vtt with hours`() {
        val vtt = """
            WEBVTT
            
            01:02:03.456 --> 01:02:05.000
            Test text
        """.trimIndent()
        
        val lines = SrtParser.parse("test-id", vtt)
        assertEquals(1, lines.size)
        // 1h = 3600000, 2m = 120000, 3s = 3000, 456ms = 456
        assertEquals(3600000L + 120000L + 3000L + 456L, lines[0].startMs)
    }
}
