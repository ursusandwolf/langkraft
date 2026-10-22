package com.langkraft.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.db.AppDatabase
import com.langkraft.data.audio.AudioDownloadCoordinator
import com.langkraft.data.audio.AudioDownloadRecovery
import com.langkraft.data.audio.FileAudioDownloadCoordinator
import com.langkraft.data.audio.JournaledFileAudioStorage
import com.langkraft.data.audio.PendingAudioStorage
import com.langkraft.data.audio.PendingAudioRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import app.cash.sqldelight.db.QueryResult

suspend fun createJvmDatabase(
    file: File,
    coordinator: AudioDownloadCoordinator = FileAudioDownloadCoordinator(File(file.absoluteFile.parentFile, "download-locks")),
    pendingStorage: PendingAudioStorage? = null
): DatabaseHandle = withContext(Dispatchers.IO) {
    file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
    val existed = file.isFile && file.length() > 0
    Class.forName("org.sqlite.JDBC")
    val driver = PersistentJdbcDriver("jdbc:sqlite:${file.absolutePath}")
    try {
        val version = driver.executeQuery(null, "PRAGMA user_version", { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0).await()
        if (existed && version < AppDatabase.Schema.version) {
            val backup = File(file.path + ".before-schema-${AppDatabase.Schema.version}.bak")
            if (!backup.exists()) driver.execute(null, "VACUUM INTO '${backup.absolutePath.replace("'", "''")}'", 0).await()
        }
        initializeDatabase(driver)
        DatabaseHandle(driver).also { handle ->
            AudioDownloadRecovery(handle.access, coordinator).recover()
            val journal = pendingStorage ?: (coordinator as? FileAudioDownloadCoordinator)?.let {
                JournaledFileAudioStorage(File(file.absoluteFile.parentFile, "audio"), it)
            }
            if (journal != null) PendingAudioRecovery(handle.access, coordinator, journal).recover()
        }
    }
    catch (error: Throwable) { driver.close(); throw error }
}
