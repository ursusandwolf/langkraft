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
    val downloadStatus: DownloadStatus = DownloadStatus.IDLE
) {
    fun getPlaybackUrl(): String {
        return if (downloadStatus == DownloadStatus.COMPLETED && localAudioPath != null) {
            localAudioPath
        } else {
            audioUrl
        }
    }
}

enum class DownloadStatus {
    IDLE, DOWNLOADING, COMPLETED, ERROR
}

data class SubtitleLine(
    val id: String,
    val contentId: String,
    val startMs: Long,
    val endMs: Long,
    val textDe: String,
    val textEn: String?
)

data class VocabularyWord(
    val id: String,
    val word: String,
    val lemma: String?,
    val translation: String?,
    val contextSentence: String,
    val contentId: String?,
    val subtitleLineId: String?,
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
