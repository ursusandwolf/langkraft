package com.langkraft.ui.dashboard

import com.langkraft.ui.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DashboardState(
    val listeningHours: Double = 12.5,
    val sentencesMastered: Int = 42,
    val weeklyGoalProgress: Float = 0.65f // 65%
)

class DashboardViewModel : BaseViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()
}
