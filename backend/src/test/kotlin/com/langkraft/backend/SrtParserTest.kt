package com.langkraft.backend
import kotlin.test.*

class SrtParserTest {
    @Test fun parsesBomCrlfAndMultilineTextWithExactMilliseconds() {
        val cues = SrtParser.parse("content", "\uFEFF1\r\n00:00:01,009 --> 00:00:03,100\r\nGuten\r\nTag!\r\n\r\n2\r\n00:00:03,100 --> 00:00:04,000\r\nHallo")
        assertEquals(2, cues.size)
        assertEquals(1009L, cues[0].startMs)
        assertEquals("Guten Tag!", cues[0].textDe)
        assertEquals("content", cues[0].contentId)
    }
    @Test fun rejectsTruncatedCueWithUsefulError() {
        val failure = assertFailsWith<IllegalArgumentException> { SrtParser.parse("c", "1") }
        assertTrue(failure.message!!.contains("cue 1"))
    }
    @Test fun rejectsInvalidTimesAndReversedIntervals() {
        for (range in listOf("00:61:00,000 --> 00:62:00,000", "00:00:02,000 --> 00:00:01,000", "invalid")) {
            assertFailsWith<IllegalArgumentException> { SrtParser.parse("c", "1\n$range\nHallo") }
        }
    }
    @Test fun emptyFileHasNoCues() { assertTrue(SrtParser.parse("c", " \r\n").isEmpty()) }
}