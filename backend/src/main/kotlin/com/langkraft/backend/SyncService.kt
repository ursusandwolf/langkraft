package com.langkraft.backend

import com.langkraft.backend.db.BackendUserRepository
import com.langkraft.backend.db.VocabularySyncRepository
import com.langkraft.domain.model.SyncRequest
import com.langkraft.domain.model.SyncResponse

class SyncService(
    private val userRepository: BackendUserRepository,
    private val vocabRepo: VocabularySyncRepository
) {
    suspend fun performSync(userEmail: String, request: SyncRequest): SyncResponse {
        val user = userRepository.findByEmail(userEmail) ?: throw UnauthorizedException("User not found")
        val serverChanges = vocabRepo.sync(user.id, request.clientChanges, request.lastSyncTimestamp)
        return SyncResponse(System.currentTimeMillis(), serverChanges)
    }
}
