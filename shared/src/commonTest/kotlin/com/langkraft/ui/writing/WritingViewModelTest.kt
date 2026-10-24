package com.langkraft.ui.writing

import com.langkraft.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WritingViewModelTest {
    @Test fun disabledAiDoesNotPretendToCorrectWriting() = runTest {
        val assistant = object : LinguisticAssistant by MockLinguisticAssistant() {
            override suspend fun capabilities() = AiCapabilities(false)
            override suspend fun correctText(text: String): CorrectionResult = error("Must not call AI")
        }
        val vm = WritingViewModel(assistant, StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            vm.onTextChanged("Ich gehen")
            vm.submitForCorrection(); runCurrent()
            assertEquals(false, vm.currentState.aiAvailable)
            assertNull(vm.currentState.correction)
            assertFalse(vm.currentState.isAnalyzing)
        } finally { vm.onCleared() }
    }
    @Test fun editingDuringRemoteRequestDiscardsOldCorrection() = runTest {
        val response = CompletableDeferred<CorrectionResult>()
        val assistant = object : LinguisticAssistant by MockLinguisticAssistant() {
            override suspend fun correctText(text: String) = withContext(NonCancellable) { response.await() }
        }
        val vm = WritingViewModel(assistant, StandardTestDispatcher(testScheduler))
        try {
            vm.onTextChanged("Ich gehen")
            vm.submitForCorrection(); runCurrent()
            assertTrue(vm.currentState.isAnalyzing)
            vm.onTextChanged("Neuer Text")
            response.complete(CorrectionResult("Ich gehen", "Ich gehe", emptyList())); runCurrent()
            assertEquals("Neuer Text", vm.currentState.inputText)
            assertNull(vm.currentState.correction)
            assertNull(vm.currentState.error)
            assertFalse(vm.currentState.isAnalyzing)
        } finally { vm.onCleared() }
    }

    @Test fun replacingRequestIgnoresLateFailureAndKeepsLatestCorrection() = runTest {
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val assistant = object : LinguisticAssistant by MockLinguisticAssistant() {
            override suspend fun correctText(text: String): CorrectionResult {
                if (++calls == 1) withContext(NonCancellable) { first.await(); error("old failure") }
                return CorrectionResult(text, "Ich gehe", emptyList())
            }
        }
        val vm = WritingViewModel(assistant, StandardTestDispatcher(testScheduler))
        try {
            vm.onTextChanged("Ich gehen")
            vm.submitForCorrection(); runCurrent()
            vm.submitForCorrection(); runCurrent()
            first.complete(Unit); runCurrent()
            assertEquals("Ich gehe", vm.currentState.correction?.correctedText)
            assertNull(vm.currentState.error)
            assertFalse(vm.currentState.isAnalyzing)
        } finally { vm.onCleared() }
    }

    @Test fun serviceErrorAllowsRetryAndClearingCancelsPendingRequest() = runTest {
        var calls = 0
        var cancelled = false
        val started = CompletableDeferred<Unit>()
        val assistant = object : LinguisticAssistant by MockLinguisticAssistant() {
            override suspend fun correctText(text: String): CorrectionResult {
                if (++calls == 1) error("Please sign in through Account (HTTP 401)")
                try { started.complete(Unit); awaitCancellation() } finally { cancelled = true }
            }
        }
        val vm = WritingViewModel(assistant, StandardTestDispatcher(testScheduler))
        try {
            vm.onTextChanged("Ich gehen")
            vm.submitForCorrection(); runCurrent()
            assertContains(vm.currentState.error.orEmpty(), "Account")
            assertFalse(vm.currentState.isAnalyzing)
            vm.submitForCorrection(); runCurrent(); started.await()
            assertNull(vm.currentState.error)
            vm.clearCorrection(); runCurrent()
            assertTrue(cancelled)
            assertNull(vm.currentState.error)
            assertFalse(vm.currentState.isAnalyzing)
        } finally { vm.onCleared() }
    }
}
