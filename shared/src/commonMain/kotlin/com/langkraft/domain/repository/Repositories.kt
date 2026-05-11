package com.langkraft.domain.repository

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
import com.langkraft.domain.model.VocabularyWord
import kotlinx.coroutines.flow.Flow

interface ContentRepository {
    fun getAllContent(): Flow<List<ImmersionContent>>
    suspend fun getContentById(id: String): ImmersionContent?
    suspend fun saveContent(content: ImmersionContent)
    suspend fun downloadAudio(content: ImmersionContent): String // Returns local path
    
    // Remote ingestion
    suspend fun fetchFromYouTube(url: String): ImmersionContent
    
    // Stats
    fun getImmersionStats(): Flow<ImmersionStats>
}

data class ImmersionStats(
    val totalContent: Long,
    val totalDurationSeconds: Long
)

interface VocabularyRepository {
    fun getWordsToReview(): Flow<List<VocabularyWord>>
    suspend fun saveWord(word: VocabularyWord)
    suspend fun deleteWord(id: String)
    
    // Sync
    suspend fun sync(lastSyncTimestamp: Long): Long // Returns new server timestamp
    
    // Stats
    fun getWordCountsByStatus(): Flow<Map<String, Long>>
    fun getWordsAddedSince(timestamp: Long): Flow<Long>
}
