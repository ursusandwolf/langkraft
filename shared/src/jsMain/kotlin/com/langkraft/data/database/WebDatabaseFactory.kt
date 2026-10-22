package com.langkraft.data.database

import com.langkraft.data.storage.createDatabaseClient
import com.langkraft.data.audio.AudioDownloadRecovery
import com.langkraft.data.audio.WebAudioDownloadCoordinator
import com.langkraft.data.audio.IndexedDbAudioStorage
import com.langkraft.data.audio.PendingAudioRecovery
import com.langkraft.data.storage.recoverIncompleteAudio
import kotlinx.coroutines.await

suspend fun createWebDatabase(): DatabaseHandle {
    val driver = BrowserSqlDriver(createDatabaseClient())
    try {
        initializeDatabase(driver)
        recoverIncompleteAudio().await()
        val handle = DatabaseHandle(driver)
        AudioDownloadRecovery(handle.access, WebAudioDownloadCoordinator()).recover()
        PendingAudioRecovery(handle.access, WebAudioDownloadCoordinator(), IndexedDbAudioStorage()).recover()
        return handle
    }
    catch (error: Throwable) { driver.close(); throw error }
}
