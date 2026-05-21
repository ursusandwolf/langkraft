package com.langkraft.backend

import com.langkraft.backend.db.IngestionJobs
import com.langkraft.domain.model.ContentProcessingStatus
import com.langkraft.domain.model.IngestionJob
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionJobRepositoryTest {

    private lateinit var db: Database
    private lateinit var repository: IngestionJobRepository

    @BeforeAll
    fun setup() {
        db = Database.connect("jdbc:sqlite:file:testdb?mode=memory&cache=shared", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(IngestionJobs)
        }
        repository = IngestionJobRepository(db)
    }

    @Test
    fun `test save and get job`() {
        val job = IngestionJob(
            jobId = "1", 
            status = ContentProcessingStatus.IDLE, 
            url = "url",
            createdAt = 1000,
            lastUpdatedAt = 1000
        )
        repository.save(job)
        
        val retrieved = repository.get("1")
        assertEquals(job.jobId, retrieved?.jobId)
        assertEquals(job.status, retrieved?.status)
        assertEquals(job.url, retrieved?.url)
    }

    @Test
    fun `test update job status`() {
        val job = IngestionJob("1", ContentProcessingStatus.IDLE, "url")
        repository.save(job)
        
        val now = System.currentTimeMillis()
        repository.update("1") { it.copy(status = ContentProcessingStatus.READY, lastUpdatedAt = now) }
        
        val updated = repository.get("1")
        assertEquals(ContentProcessingStatus.READY, updated?.status)
        assertEquals(now, updated?.lastUpdatedAt)
    }

    @Test
    fun `test cleanup`() {
        val oldJob = IngestionJob("1", ContentProcessingStatus.IDLE, "url", createdAt = 100, lastUpdatedAt = 100)
        val newJob = IngestionJob("2", ContentProcessingStatus.IDLE, "url", createdAt = 5000, lastUpdatedAt = 5000)
        
        repository.save(oldJob)
        repository.save(newJob)
        
        // Cleanup older than 1000ms
        repository.cleanup(1000)
        
        assertNull(repository.get("1"))
        assertEquals("2", repository.get("2")?.jobId)
    }
}
