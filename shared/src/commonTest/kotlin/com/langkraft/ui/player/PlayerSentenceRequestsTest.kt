package com.langkraft.ui.player

import com.langkraft.domain.ai.*
import com.langkraft.domain.model.SubtitleLine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSentenceRequestsTest {
    private val first = SubtitleLine("first", "video", 0, 1000, "Das Haus", null)
    private val second = first.copy(id = "second", originalText = "Das Buch")
    private val analysis = DeepAnalysisResult(listOf(AnalyzedWord("Haus", "Haus", "noun", "subject")), "syntax")

    @Test fun translationsFinishIndependentlyInReverseOrderAndIgnoreDuplicateClicks() = runTest {
        val responses = mapOf(first.originalText to CompletableDeferred<String>(), second.originalText to CompletableDeferred())
        var calls = 0
        val fixture = Fixture(backgroundScope, translate = { calls++; responses.getValue(it).await() })
        fixture.delegate.handleToggleTranslation(first)
        fixture.delegate.handleToggleTranslation(second)
        fixture.delegate.handleToggleTranslation(first)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(setOf(first.id, second.id), fixture.state.translatingSentenceIds)

        responses.getValue(second.originalText).complete("The book")
        runCurrent()
        assertEquals(setOf(first.id), fixture.state.translatingSentenceIds)
        assertEquals("The book", fixture.state.sentenceTranslations[second.id])
        responses.getValue(first.originalText).complete("The house")
        runCurrent()
        assertTrue(fixture.state.translatingSentenceIds.isEmpty())
        assertEquals("The house", fixture.state.sentenceTranslations[first.id])
        fixture.delegate.handleToggleTranslation(first)
        assertFalse(first.id in fixture.state.sentenceTranslations)
        assertEquals(2, calls)
    }

    @Test fun failedTranslationKeepsOtherIndicatorAndAllowsRetry() = runTest {
        val pending = CompletableDeferred<String>()
        var fail = true
        val fixture = Fixture(backgroundScope, translate = {
            if (it == second.originalText) pending.await()
            else if (fail) error("Unavailable") else "The house"
        })
        fixture.delegate.handleToggleTranslation(first)
        fixture.delegate.handleToggleTranslation(second)
        runCurrent()
        assertEquals(setOf(second.id), fixture.state.translatingSentenceIds)
        assertEquals("Unavailable", fixture.state.error)
        fail = false
        fixture.delegate.handleToggleTranslation(first)
        runCurrent()
        assertEquals("The house", fixture.state.sentenceTranslations[first.id])
        assertEquals(setOf(second.id), fixture.state.translatingSentenceIds)
        pending.complete("The book")
        runCurrent()
        assertTrue(fixture.state.translatingSentenceIds.isEmpty())
    }

    @Test fun lemmasFinishIndependentlyAndDoNotClearTranslationIndicator() = runTest {
        val firstAnalysis = CompletableDeferred<DeepAnalysisResult>()
        val secondAnalysis = CompletableDeferred<DeepAnalysisResult>()
        val translation = CompletableDeferred<String>()
        var calls = 0
        val fixture = Fixture(backgroundScope, translate = { translation.await() }, analyze = {
            calls++
            if (it == first.originalText) firstAnalysis.await() else secondAnalysis.await()
        })
        fixture.delegate.handleToggleLemmatization(first)
        fixture.delegate.handleToggleLemmatization(second)
        fixture.delegate.handleToggleLemmatization(first)
        fixture.delegate.handleToggleTranslation(first)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(setOf(first.id, second.id), fixture.state.lemmatizingSentenceIds)
        secondAnalysis.complete(analysis)
        runCurrent()
        assertEquals(setOf(first.id), fixture.state.lemmatizingSentenceIds)
        assertEquals(mapOf("Haus" to "Haus"), fixture.state.lemmatizedSentences[second.id])
        firstAnalysis.complete(analysis)
        runCurrent()
        assertTrue(fixture.state.lemmatizingSentenceIds.isEmpty())
        assertEquals(setOf(first.id), fixture.state.translatingSentenceIds)
        translation.complete("The house")
        runCurrent()
        fixture.delegate.handleToggleLemmatization(first)
        assertFalse(first.id in fixture.state.lemmatizedSentences)
        assertEquals(2, calls)
    }

    @Test fun failedLemmaKeepsOtherIndicatorAndAllowsRetry() = runTest {
        val pending = CompletableDeferred<DeepAnalysisResult>()
        var fail = true
        val fixture = Fixture(backgroundScope, analyze = {
            if (it == second.originalText) pending.await()
            else if (fail) error("Unavailable") else analysis
        })
        fixture.delegate.handleToggleLemmatization(first)
        fixture.delegate.handleToggleLemmatization(second)
        runCurrent()
        assertEquals(setOf(second.id), fixture.state.lemmatizingSentenceIds)
        assertEquals("Unavailable", fixture.state.error)
        fail = false
        fixture.delegate.handleToggleLemmatization(first)
        runCurrent()
        assertEquals(mapOf("Haus" to "Haus"), fixture.state.lemmatizedSentences[first.id])
        assertEquals(setOf(second.id), fixture.state.lemmatizingSentenceIds)
        pending.complete(analysis)
        runCurrent()
        assertTrue(fixture.state.lemmatizingSentenceIds.isEmpty())
    }

    @Test fun resetIgnoresOldResultsAndCleanupForReusedLineIds() = runTest {
        val oldTranslation = CompletableDeferred<String>()
        val newTranslation = CompletableDeferred<String>()
        val oldAnalysis = CompletableDeferred<DeepAnalysisResult>()
        val newAnalysis = CompletableDeferred<DeepAnalysisResult>()
        var translations = 0
        var analyses = 0
        val fixture = Fixture(backgroundScope,
            translate = { if (++translations == 1) oldTranslation.await() else newTranslation.await() },
            analyze = { if (++analyses == 1) oldAnalysis.await() else newAnalysis.await() })
        fixture.delegate.handleToggleTranslation(first)
        fixture.delegate.handleToggleLemmatization(first)
        runCurrent()
        fixture.delegate.reset()
        assertTrue(fixture.state.translatingSentenceIds.isEmpty())
        assertTrue(fixture.state.lemmatizingSentenceIds.isEmpty())
        fixture.delegate.handleToggleTranslation(first)
        fixture.delegate.handleToggleLemmatization(first)
        runCurrent()
        oldTranslation.complete("Stale translation")
        oldAnalysis.complete(analysis)
        runCurrent()
        assertTrue(fixture.state.sentenceTranslations.isEmpty())
        assertTrue(fixture.state.lemmatizedSentences.isEmpty())
        assertEquals(setOf(first.id), fixture.state.translatingSentenceIds)
        assertEquals(setOf(first.id), fixture.state.lemmatizingSentenceIds)
        newTranslation.complete("Current translation")
        newAnalysis.complete(analysis)
        runCurrent()
        assertEquals("Current translation", fixture.state.sentenceTranslations[first.id])
        assertTrue(fixture.state.translatingSentenceIds.isEmpty())
        assertTrue(fixture.state.lemmatizingSentenceIds.isEmpty())
    }

    @Test fun cancellationClearsBothIndicatorsWithoutShowingAnError() = runTest {
        val fixture = Fixture(backgroundScope,
            translate = { awaitCancellation() }, analyze = { awaitCancellation() })
        fixture.delegate.handleToggleTranslation(first)
        fixture.delegate.handleToggleLemmatization(second)
        runCurrent()
        backgroundScope.cancel()
        runCurrent()
        assertTrue(fixture.state.translatingSentenceIds.isEmpty())
        assertTrue(fixture.state.lemmatizingSentenceIds.isEmpty())
        assertNull(fixture.state.error)
    }

    private class Fixture(
        scope: CoroutineScope,
        translate: suspend (String) -> String = { "translation" },
        analyze: suspend (String) -> DeepAnalysisResult = { DeepAnalysisResult(emptyList(), "syntax") }
    ) {
        var state = PlayerState()
        val delegate = PlayerLinguisticDelegate(object : LinguisticAssistant {
            override suspend fun translateSentence(text: String) = translate(text)
            override suspend fun analyzeSentence(text: String) = analyze(text)
            override suspend fun translateWord(word: String, context: String): TranslationResult = error("Unused")
            override suspend fun correctText(text: String): CorrectionResult = error("Unused")
        }, scope, { update -> state = update(state) }, { state })
    }
}
