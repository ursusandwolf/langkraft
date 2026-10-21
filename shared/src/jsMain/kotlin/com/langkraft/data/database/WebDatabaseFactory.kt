package com.langkraft.data.database

import com.langkraft.data.storage.createDatabaseClient

suspend fun createWebDatabase(): DatabaseHandle {
    val driver = BrowserSqlDriver(createDatabaseClient())
    try { initializeDatabase(driver); return DatabaseHandle(driver) }
    catch (error: Throwable) { driver.close(); throw error }
}
