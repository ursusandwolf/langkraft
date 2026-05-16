package com.langkraft.ui

sealed class Screen {
    data object Dashboard : Screen()
    data object ContentSelection : Screen()
    data object SrsTraining : Screen()
    data class Player(val contentId: String) : Screen()
    data object Writing : Screen()
}
