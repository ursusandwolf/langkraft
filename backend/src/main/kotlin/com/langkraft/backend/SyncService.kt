package com.langkraft.backend

import com.langkraft.backend.db.BackendUserRepository
import com.langkraft.backend.db.VocabularySyncRepository
import com.langkraft.domain.model.SyncRequest
import com.langkraft.domain.model.SyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncService(
    private val userRepository: BackendUserRepository,
    private val vocabRepo: VocabularySyncRepository
) {
    suspend fun performSync(userEmail: String, request: SyncRequest): SyncResponse =
        withContext(Dispatchers.IO) {
            val user = userRepository.findByEmail(userEmail) ?: throw UnauthorizedException("User not found")
            vocabRepo.synchronize(user.id, request.clientChanges, request.lastSyncTimestamp)
        }
}
