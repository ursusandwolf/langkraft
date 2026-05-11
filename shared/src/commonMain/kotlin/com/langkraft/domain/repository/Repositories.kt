package com.langkraft.domain.repository

import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.SubtitleLine
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

// For backward compatibility during migration, we can keep ContentRepository 
// but it should probably inherit or be phased out.
// Given this is a refactoring, let's see where it's used.
// Most ViewModels probably only need LocalContentRepository.

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
    fun getWordCountsByStatus(): Flow<Map<WordStatus, Long>>
    fun getWordsAddedSince(timestamp: Long): Flow<Long>
}
