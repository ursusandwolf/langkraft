package com.langkraft.domain.repository

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.VocabularyWord
import com.langkraft.domain.model.WordStatus
import kotlinx.coroutines.flow.Flow

interface LocalContentRepository {
    fun getAllContent(): Flow<List<ImmersionContent>>
    suspend fun getContentById(id: String): ImmersionContent?
    suspend fun saveContent(content: ImmersionContent)
    fun getImmersionStats(): Flow<ImmersionStats>
}

interface RemoteContentSource {
    suspend fun fetchFromYouTube(url: String): ImmersionContent
}

interface AudioDownloader {
    suspend fun downloadAudio(content: ImmersionContent): String // Returns local path
}

data class ImmersionStats(
    val totalContent: Long,
    val totalDurationSeconds: Long
)

interface VocabularyRepository {
    fun getWordsToReview(): Flow<List<VocabularyWord>>
    fun getReviewCount(): Flow<Int>
    suspend fun saveWord(word: VocabularyWord)
    suspend fun deleteWord(id: String)
    
    // Sync
    suspend fun sync(lastSyncTimestamp: Long): Long // Returns new server timestamp
    
    // Stats
    fun getWordCountsByStatus(): Flow<Map<WordStatus, Long>>
    fun getWordsAddedSince(timestamp: Long): Flow<Long>

    // Metadata
    suspend fun getSyncMetadata(key: String): String?
    suspend fun setSyncMetadata(key: String, value: String)
}
