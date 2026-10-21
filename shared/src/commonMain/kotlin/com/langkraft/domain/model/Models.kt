package com.langkraft.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ImmersionContent(
    val id: String,
    val title: String,
    val audioUrl: String,
    val localAudioPath: String?,
    val sourceUrl: String,
    val durationSeconds: Long,
    val subtitles: List<SubtitleLine> = emptyList()
)

@Serializable
data class SubtitleLine(
    val id: String,
    val contentId: String,
    val startMs: Long,
    val endMs: Long,
    val textDe: String,
    val textEn: String?
)

@Serializable
data class VocabularyWord(
    val id: String,
    val word: String,
    val lemma: String?,
    val translation: String?,
    val contextSentence: String,
    val contentId: String?,
    val subtitleLineId: String?,
    val status: WordStatus = WordStatus.NEW
)

@Serializable
enum class WordStatus {
    NEW, LEARNING, MASTERED
}
