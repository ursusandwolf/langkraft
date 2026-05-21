package com.langkraft.backend

import com.langkraft.backend.db.IngestionJobs
import com.langkraft.domain.model.ImmersionContent
import com.langkraft.domain.model.IngestionJob
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Repository to manage IngestionJob lifecycle backed by a persistent database.
 */
class IngestionJobRepository(private val db: Database) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun toIngestionJob(row: ResultRow): IngestionJob {
        val contentJson = row.getOrNull(IngestionJobs.contentJson)
        val content = contentJson?.let { json.decodeFromString<ImmersionContent>(it) }

        return IngestionJob(
            jobId = row[IngestionJobs.jobId],
            status = row[IngestionJobs.status],
            url = row[IngestionJobs.url],
            content = content,
            error = row.getOrNull(IngestionJobs.error),
            createdAt = row[IngestionJobs.createdAt],
            lastUpdatedAt = row[IngestionJobs.lastUpdatedAt]
        )
    }

    fun save(job: IngestionJob) {
        transaction(db) {
            IngestionJobs.insert {
                it[jobId] = job.jobId
                it[status] = job.status
                it[url] = job.url
                it[createdAt] = job.createdAt
                it[lastUpdatedAt] = job.lastUpdatedAt
            }
        }
    }

    fun get(id: String): IngestionJob? {
        return transaction(db) {
            IngestionJobs.select { IngestionJobs.jobId eq id }
                .map(::toIngestionJob)
                .singleOrNull()
        }
    }

    fun update(id: String, modifier: (IngestionJob) -> IngestionJob) {
        transaction(db) {
            val currentJob = IngestionJobs.select { IngestionJobs.jobId eq id }
                .map(::toIngestionJob)
                .singleOrNull() ?: return@transaction

            val updatedJob = modifier(currentJob)

            IngestionJobs.update({ IngestionJobs.jobId eq id }) {
                it[status] = updatedJob.status
                it[error] = updatedJob.error
                updatedJob.content?.let { content ->
                    it[contentJson] = json.encodeToString(content)
                }
                it[lastUpdatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun cleanup(expiryMillis: Long) {
        transaction(db) {
            val expiryTime = System.currentTimeMillis() - expiryMillis
            IngestionJobs.deleteWhere { IngestionJobs.lastUpdatedAt lessEq expiryTime }
        }
    }
}
