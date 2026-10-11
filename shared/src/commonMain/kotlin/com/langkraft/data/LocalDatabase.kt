package com.langkraft.data

import com.langkraft.db.AppDatabase
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex

class LocalDatabase(val driver: SqlDriver) {
    val database = AppDatabase(driver)
    val mutex = Mutex()
    fun close() { driver.close() }
}