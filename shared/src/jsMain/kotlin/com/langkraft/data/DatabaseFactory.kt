package com.langkraft.data

import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.langkraft.db.AppDatabase
import org.w3c.dom.Worker

suspend fun createBrowserDatabase(): LocalDatabase {
    val driver = WebWorkerDriver(Worker("database.worker.js"))
    try {
        AppDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        return LocalDatabase(driver)
    } catch (failure: Throwable) {
        driver.close()
        throw failure
    }
}