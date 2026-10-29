package com.langkraft.backend

import com.langkraft.backend.db.BackendUserRepository
import com.langkraft.backend.db.VocabularySyncRepository
import com.langkraft.domain.model.SyncRequest
import com.langkraft.domain.model.SyncResponse

class SyncService(
    private val userRepository: BackendUserRepository,
    private val vocabRepo: VocabularySyncRepository,
    private val work: BackendWork
) {
    suspend fun performSync(userId: String, request: SyncRequest): SyncResponse {
        validateSync(request)
        return work.jdbc {
            val user = userRepository.findById(userId) ?: throw UnauthorizedException("User not found")
            vocabRepo.synchronize(user.id, request.clientChanges, request.lastSyncTimestamp)
        }
    }
}
