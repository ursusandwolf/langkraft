package com.langkraft.data.database

import com.langkraft.data.storage.createDatabaseClient
import com.langkraft.data.storage.recoverIncompleteAudio
import kotlinx.coroutines.await

suspend fun createWebDatabase(): DatabaseHandle {
    val driver = BrowserSqlDriver(createDatabaseClient())
    try {
        initializeDatabase(driver)
        recoverIncompleteAudio().await()
        return DatabaseHandle(driver)
    }
    catch (error: Throwable) { driver.close(); throw error }
}
