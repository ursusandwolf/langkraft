package com.langkraft.ui.player

import com.langkraft.domain.model.SubtitleLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSubtitleNavigationTest {
    private val cues = listOf(
        SubtitleLine("a", "v", 0, 1_000, "one", null),
        SubtitleLine("b", "v", 1_200, 2_200, "two", null),
        SubtitleLine("c", "v", 2_500, 3_500, "three", null)
    )

    @Test
    fun stepBackRestartsCurrentCueWhenMoreThanOneSecondIn() {
        assertEquals(1_200, resolveStepBackTarget(cues, currentIndex = 1, currentTimeMs = 1_200 + 1_001))
    }

    @Test
    fun stepBackGoesToPreviousCueNearStart() {
        assertEquals(0, resolveStepBackTarget(cues, currentIndex = 1, currentTimeMs = 1_250))
    }

    @Test
    fun stepBackOutsideCueUsesLastEnded() {
        assertEquals(1_200, resolveStepBackTarget(cues, currentIndex = -1, currentTimeMs = 2_400))
    }

    @Test
    fun loopSeeksNearEndOfActiveCue() {
        val loop = resolveLoopSeek(timeMs = 2_200 - 200, subtitles = cues, activeIndex = 1)
        assertEquals(LoopSeek(seekMs = 1_200, subtitleIndex = 1), loop)
    }

    @Test
    fun loopSeeksJustPastEndedCue() {
        val loop = resolveLoopSeek(timeMs = 2_250, subtitles = cues, activeIndex = -1)
        assertEquals(LoopSeek(seekMs = 1_200, subtitleIndex = 1), loop)
    }

    @Test
    fun loopDoesNothingInMiddleOfCue() {
        assertNull(resolveLoopSeek(timeMs = 1_500, subtitles = cues, activeIndex = 1))
    }
}
