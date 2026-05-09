package com.langkraft.ui.player

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine

data class PlayerState(
    val isLoading: Boolean = false,
    val content: ImmersionContent? = null,
    val currentTimeMs: Long = 0,
    val isPlaying: Boolean = false,
    val selectedWord: String? = null,
    val selectedWordContext: SubtitleLine? = null,
    val translation: String? = null,
    val isTranslating: Boolean = false,
    val error: String? = null
)

sealed class PlayerEvent {
    data class LoadContent(val contentId: String) : PlayerEvent()
    object PlayPause : PlayerEvent()
    data class SeekTo(val timeMs: Long) : PlayerEvent()
    data class WordClicked(val word: String, val line: SubtitleLine) : PlayerEvent()
    object DismissWordDetails : PlayerEvent()
}
