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

    @Test
    fun `test parse Easy German snippet`() {
        val srt = """
            1
            00:00:01,200 --> 00:00:04,500
            Hallo, willkommen bei Easy German!
            
            2
            00:00:04,600 --> 00:00:08,000
            Heute testen wir, wie gut ihr schnelles Deutsch versteht.
            
            3
            00:00:08,150 --> 00:00:12,300
            Viel Spaß beim Lernen и Viel Erfolg!
        """.trimIndent()
        
        val lines = SrtParser.parse("doFTT_aFPYI", srt)
        
        assertEquals(3, lines.size)
        assertEquals("doFTT_aFPYI", lines[0].contentId)
        
        // Check line 1
        assertEquals("Hallo, willkommen bei Easy German!", lines[0].textDe)
        assertEquals(1200L, lines[0].startMs)
        assertEquals(4500L, lines[0].endMs)
        
        // Check line 2 (German grammar check)
        assertTrue(lines[1].textDe.contains("versteht"))
        assertEquals(4600L, lines[1].startMs)
        
        // Check line 3 (Special characters and millis)
        assertTrue(lines[2].textDe.contains("Spaß"))
        assertEquals(8150L, lines[2].startMs)
    }
}
