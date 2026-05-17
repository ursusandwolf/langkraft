package com.langkraft.backend

import com.langkraft.domain.model.IngestionJob
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository to manage IngestionJob lifecycle.
 */
class IngestionJobRepository {
    private val jobs = ConcurrentHashMap<String, IngestionJob>()
    private val jobTimestamps = ConcurrentHashMap<String, Long>()

    fun save(job: IngestionJob) {
        jobs[job.jobId] = job
        jobTimestamps[job.jobId] = System.currentTimeMillis()
    }

    fun get(jobId: String): IngestionJob? = jobs[jobId]

    fun update(jobId: String, modifier: (IngestionJob) -> IngestionJob) {
        jobs.computeIfPresent(jobId) { _, current ->
            val updated = modifier(current)
            jobTimestamps[jobId] = System.currentTimeMillis()
            updated
        }
    }

    fun cleanup(expiryMillis: Long) {
        val now = System.currentTimeMillis()
        val expiredJobIds = jobTimestamps.filter { (_, timestamp) -> now - timestamp > expiryMillis }.keys
        expiredJobIds.forEach {
            jobs.remove(it)
            jobTimestamps.remove(it)
        }
    }
}
