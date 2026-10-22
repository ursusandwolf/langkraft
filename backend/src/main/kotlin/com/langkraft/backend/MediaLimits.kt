package com.langkraft.backend

import io.ktor.server.config.ApplicationConfig

data class MediaLimits(
    val maxImportBytes: Long = 0,
    val maxTotalBytes: Long = 0,
    val maxDurationSeconds: Long = 14_400,
    val checkIntervalMs: Long = 200
) {
    init {
        require(maxImportBytes >= 0 && maxTotalBytes >= 0) { "Media byte limits must be non-negative" }
        require(maxTotalBytes == 0L || maxImportBytes in 1..maxTotalBytes) {
            "A total media budget requires a positive import budget no larger than the total"
        }
        require(maxDurationSeconds in 1..86_400) { "Maximum duration must be between 1 second and 24 hours" }
        require(checkIntervalMs in 10..60_000) { "Media check interval must be between 10 and 60000 ms" }
    }

    val enabled get() = maxImportBytes > 0 || maxTotalBytes > 0

    companion object {
        fun from(config: ApplicationConfig) = MediaLimits(
            maxImportBytes = config.propertyOrNull("storage.max_import_bytes")?.getString()?.toLong() ?: 0,
            maxTotalBytes = config.propertyOrNull("storage.max_total_bytes")?.getString()?.toLong() ?: 0,
            maxDurationSeconds = config.propertyOrNull("ingestion.max_duration_seconds")?.getString()?.toLong() ?: 14_400,
            checkIntervalMs = config.propertyOrNull("storage.budget_check_interval_ms")?.getString()?.toLong() ?: 200
        )
    }
}
