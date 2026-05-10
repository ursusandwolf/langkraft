package com.langkraft.ui.player

import com.langkraft.domain.ai.DeepAnalysisResult
import com.langkraft.domain.ai.TranslationResult
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine

data class PlayerState(
    val isLoading: Boolean = false,
    val content: ImmersionContent? = null,
    val currentTimeMs: Long = 0,
    val isPlaying: Boolean = false,
    val isLooping: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val selectedWord: String? = null,
    val selectedWordContext: SubtitleLine? = null,
    val wordTranslation: TranslationResult? = null,
    val isTranslatingWord: Boolean = false,
    val deepAnalysis: DeepAnalysisResult? = null,
    val isAnalyzing: Boolean = false,
    val sentenceTranslations: Map<String, String> = emptyMap(), // subtitle line id -> translation
    val analyzingSentenceId: String? = null,
    val memorizationText: String? = null,
    val error: String? = null
)

sealed class PlayerEvent {
    data class LoadContent(val contentId: String) : PlayerEvent()
    object PlayPause : PlayerEvent()
    object ToggleLoop : PlayerEvent()
    data class SetPlaybackSpeed(val speed: Float) : PlayerEvent()
    data class SeekTo(val timeMs: Long) : PlayerEvent()
    data class WordClicked(val word: String, val line: SubtitleLine) : PlayerEvent()
    data class DeepAnalysisClicked(val line: SubtitleLine) : PlayerEvent()
    data class ToggleTranslation(val line: SubtitleLine) : PlayerEvent()
    data class MemorizationClicked(val text: String) : PlayerEvent()
    object DismissWordDetails : PlayerEvent()
    object DismissDeepAnalysis : PlayerEvent()
    object DismissMemorization : PlayerEvent()
}
