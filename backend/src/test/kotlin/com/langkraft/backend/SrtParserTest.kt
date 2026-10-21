package com.langkraft.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SrtParserTest {
    private fun cue(index: String = "1", range: String = "00:00:01,123 --> 00:00:02,456", text: String = "Guten Tag") =
        "$index\n$range\n$text"

    private fun assertInvalid(source: String) {
        val failure = assertFailsWith<SubtitleParseException> { SrtParser.parse("video", source) }
        assertTrue(failure.message.orEmpty().isNotBlank())
    }

    @Test fun acceptsLineEndingsBomWhitespaceAndMultilineText() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val source = "\uFEFF\n \n${cue(text = " Guten Tag \n zusammen ")}\n\n \n${cue("3")}\n\n"
            val result = SrtParser.parse("video", source.replace("\n", newline))
            assertEquals(listOf("Guten Tag zusammen", "Guten Tag"), result.map { it.textDe })
            assertEquals(listOf(1123L, 1123L), result.map { it.startMs })
            assertEquals(listOf(2456L, 2456L), result.map { it.endMs })
            assertTrue(result.all { it.contentId == "video" && it.textEn == null && it.id.isNotBlank() })
            assertEquals(2, result.map { it.id }.toSet().size)
        }
    }

    @Test fun usesExactIntegerMillisecondsForLongTimestamps() {
        val parsed = SrtParser.parse("video", cue(range = "123:59:59,001 --> 124:00:00,999")).single()
        assertEquals(446399001L, parsed.startMs)
        assertEquals(446400999L, parsed.endMs)
    }

    @Test fun preservesDotTimestampCompatibilityAndFlexibleArrowSpacing() {
        val parsed = SrtParser.parse("video", cue(range = "00:00:01.001\t-->00:00:01.002")).single()
        assertEquals(1001L, parsed.startMs)
        assertEquals(1002L, parsed.endMs)
    }

    @Test fun sortsUnorderedCuesAndPreservesOverlapsAndStableTies() {
        val source = listOf(
            cue("1", "00:00:03,000 --> 00:00:04,000", "Später"),
            cue("2", "00:00:01,000 --> 00:00:03,500", "Zuerst"),
            cue("3", "00:00:01,000 --> 00:00:02,000", "Gleichzeitig")
        ).joinToString("\n\n")
        val parsed = SrtParser.parse("video", source)
        assertEquals(listOf("Zuerst", "Gleichzeitig", "Später"), parsed.map { it.textDe })
        assertEquals(listOf(3500L, 2000L, 4000L), parsed.map { it.endMs })
    }

    @Test fun rejectsEmptyAndBomOnlyFiles() {
        for (source in listOf("", " \n\t\r\n", "\uFEFF", "\uFEFF\n\n")) assertInvalid(source)
    }

    @Test fun rejectsInvalidIndices() {
        for (index in listOf("0", "-1", "1.5", "cue", "999999999999999999999999999")) {
            assertInvalid(cue(index))
        }
        assertInvalid("00:00:01,000 --> 00:00:02,000\nText")
    }

    @Test fun rejectsDuplicateAndDecreasingIndices() {
        for (index in listOf("2", "1")) assertInvalid(cue("2") + "\n\n" + cue(index))
    }

    @Test fun rejectsMalformedTimestampsAndRanges() {
        for (range in listOf(
            "00:00:01,000", "00:00:01,000 -> 00:00:02,000",
            "00:00:01,000 -->", "--> 00:00:02,000",
            "00:60:00,000 --> 01:00:01,000", "00:00:60,000 --> 00:01:01,000",
            "-01:00:00,000 --> 00:00:01,000", "00:00:NaN --> 00:00:02,000",
            "00:00:01,1 --> 00:00:02,000", "00:00:01,0000 --> 00:00:02,000",
            "00:01,000 --> 00:02,000", "00:00:01,000 --> 00:00:02,000 junk"
        )) assertInvalid(cue(range = range))
    }

    @Test fun rejectsOverflowingTimestamps() {
        assertInvalid(cue(range = "9999999999999999999999:00:00,000 --> 9999999999999999999999:00:01,000"))
        assertInvalid(cue(range = "2562047788015:12:55,808 --> 2562047788015:12:56,000"))
    }

    @Test fun rejectsTruncatedBlocksAndEmptyText() {
        for (source in listOf("1", "1\n", cue(text = ""), cue(text = " \n\t"), cue() + "\n\n2")) {
            assertInvalid(source)
        }
    }

    @Test fun rejectsReversedAndZeroLengthIntervals() {
        assertInvalid(cue(range = "00:00:02,000 --> 00:00:01,000"))
        assertInvalid(cue(range = "00:00:01,000 --> 00:00:01,000"))
    }

    @Test fun rejectsMissingBlankSeparatorInsteadOfSwallowingTheNextCue() {
        assertInvalid(cue() + "\n" + cue("2"))
    }

    @Test fun rejectsRawWebVttWhichMustBeConvertedBeforeParsing() {
        assertInvalid("WEBVTT\n\n00:01.000 --> 00:02.000\nHallo")
    }

    @Test fun reportsTheLineOfTheInvalidBlock() {
        val failure = assertFailsWith<SubtitleParseException> { SrtParser.parse("video", cue() + "\n\n2") }
        assertTrue(failure.message.orEmpty().contains("line 5"))
    }
}
