package com.langkraft.domain.model

data class ImmersionContent(
    val id: String,
    val title: String,
    val audioUrl: String,
    val localAudioPath: String?,
    val sourceUrl: String,
    val durationSeconds: Long,
    val subtitles: List<SubtitleLine> = emptyList(),
    val waveform: List<Float> = emptyList(), // Normalized amplitudes [0.0 - 1.0]
    val downloadStatus: DownloadStatus = DownloadStatus.IDLE,
    val contentLanguage: Language = Language.DE,
    val translationLanguage: Language = Language.EN
) {
    fun getPlaybackUrl(): String {
        return if (downloadStatus == DownloadStatus.COMPLETED && localAudioPath != null) {
            localAudioPath
        } else {
            audioUrl
        }
    }
}

enum class Language { DE, EN, ES, RU, FR }

enum class DownloadStatus {
    IDLE, DOWNLOADING, COMPLETED, ERROR
}

data class SubtitleLine(
    val id: String,
    val contentId: String,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translationText: String?
)

@kotlinx.serialization.Serializable
data class VocabularyWord(
    val id: String,
    val word: String,
    val lemma: String? = null,
    val translation: String? = null,
    val contextSentence: String,
    val contentId: String? = null,
    val subtitleLineId: String? = null,
    val addedAt: Long = 0,
    val status: WordStatus = WordStatus.NEW,
    val nextReviewMs: Long = 0,
    val intervalDays: Int = 0,
    val easeFactor: Double = 2.5,
    val lastUpdated: Long = 0
)

@kotlinx.serialization.Serializable
enum class WordStatus {
    NEW, LEARNING, MASTERED
}
