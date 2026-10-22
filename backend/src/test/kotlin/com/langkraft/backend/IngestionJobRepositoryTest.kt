package com.langkraft.backend

import com.langkraft.backend.db.IngestionJobs
import com.langkraft.domain.model.ContentProcessingStatus
import com.langkraft.domain.model.IngestionJob
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestionJobRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: Database
    private lateinit var repository: IngestionJobRepository

    @BeforeEach
    fun setup() {
        db = Database.connect("jdbc:sqlite:${tempDir.resolve("jobs.db")}", driver = "org.sqlite.JDBC")
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
        assertTrue(requireNotNull(updated).lastUpdatedAt in now..System.currentTimeMillis())
    }

    @Test
    fun `test cleanup`() {
        val now = System.currentTimeMillis()
        val expiryMs = 30L * 24 * 60 * 60 * 1000
        val oldJob = IngestionJob("1", ContentProcessingStatus.IDLE, "url", createdAt = now - 2 * expiryMs, lastUpdatedAt = now - 2 * expiryMs)
        val newJob = IngestionJob("2", ContentProcessingStatus.IDLE, "url", createdAt = now, lastUpdatedAt = now)
        
        repository.save(oldJob)
        repository.save(newJob)
        
        // Keep recent jobs while removing expired ones.
        repository.cleanup(expiryMs)
        
        assertNull(repository.get("1"))
        assertEquals("2", repository.get("2")?.jobId)
    }
}
