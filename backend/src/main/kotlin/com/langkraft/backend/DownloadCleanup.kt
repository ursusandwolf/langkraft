package com.langkraft.backend

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Utility to clean up old files in the downloads directory.
 */
object DownloadCleanup {
    private val logger = LoggerFactory.getLogger(DownloadCleanup::class.java)

    fun cleanup(downloadsDir: String, olderThanDays: Int = 7) {
        val directory = File(downloadsDir)
        if (!directory.exists() || !directory.isDirectory) return

        val now = System.currentTimeMillis()
        val expiry = TimeUnit.DAYS.toMillis(olderThanDays.toLong())

        directory.listFiles()?.forEach { file ->
            if (now - file.lastModified() > expiry) {
                if (file.delete()) {
                    logger.info("Deleted old download: ${file.name}")
                }
            }
        }
    }
}
