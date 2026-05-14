package com.langkraft.ui.dashboard

import com.langkraft.domain.model.WordStatus
import com.langkraft.domain.repository.LocalContentRepository
import com.langkraft.domain.repository.VocabularyRepository
import com.langkraft.data.sync.SyncManager
import com.langkraft.ui.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class DashboardState(
    val totalContent: Long = 0,
    val totalImmersionSeconds: Long = 0,
    val wordsMastered: Long = 0,
    val wordsLearning: Long = 0,
    val wordsNew: Long = 0,
    val wordsToReviewToday: Int = 0,
    val wordsAddedThisWeek: Long = 0
)

class DashboardViewModel(
    private val contentRepository: LocalContentRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val syncManager: SyncManager
) : BaseViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        loadStats()
        scope.launch {
            syncManager.sync()
        }
    }

    private fun loadStats() {
        val oneWeekAgo = Clock.System.now().toEpochMilliseconds() - (7 * 24 * 60 * 60 * 1000L)
        
        combine(
            contentRepository.getImmersionStats(),
            vocabularyRepository.getWordCountsByStatus(),
            vocabularyRepository.getReviewCount(),
            vocabularyRepository.getWordsAddedSince(oneWeekAgo)
        ) { immersion, counts, reviewCount, recent ->
            DashboardState(
                totalContent = immersion.totalContent,
                totalImmersionSeconds = immersion.totalDurationSeconds,
                wordsMastered = counts[WordStatus.MASTERED] ?: 0,
                wordsLearning = counts[WordStatus.LEARNING] ?: 0,
                wordsNew = counts[WordStatus.NEW] ?: 0,
                wordsToReviewToday = reviewCount,
                wordsAddedThisWeek = recent
            )
        }.onEach { newState ->
            _state.value = newState
        }.launchIn(scope)
    }
}
