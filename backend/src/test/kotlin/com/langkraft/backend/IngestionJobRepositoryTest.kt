package com.langkraft.backend

import com.langkraft.domain.model.ContentProcessingStatus
import com.langkraft.domain.model.IngestionJob
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IngestionJobRepositoryTest {

    private val repository = IngestionJobRepository()

    @Test
    fun `test save and get job`() {
        val job = IngestionJob("1", ContentProcessingStatus.IDLE, "url")
        repository.save(job)
        assertEquals(job, repository.get("1"))
    }

    @Test
    fun `test update job status`() {
        val job = IngestionJob("1", ContentProcessingStatus.IDLE, "url")
        repository.save(job)
        repository.update("1") { it.copy(status = ContentProcessingStatus.READY) }
        assertEquals(ContentProcessingStatus.READY, repository.get("1")?.status)
    }

    @Test
    fun `test cleanup`() {
        val job = IngestionJob("1", ContentProcessingStatus.IDLE, "url")
        repository.save(job)
        
        // Wait a small amount of time to ensure system time has advanced past the save time
        Thread.sleep(10)
        
        repository.cleanup(0) // Cleanup everything older than now (which is everything)
        assertNull(repository.get("1"))
    }
}
