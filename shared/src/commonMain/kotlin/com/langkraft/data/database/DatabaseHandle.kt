package com.langkraft.data.database

import app.cash.sqldelight.db.SqlDriver
import com.langkraft.db.AppDatabase

/** Owns one driver; repositories using this database must be stopped before closing it. */
class DatabaseHandle(private val driver: SqlDriver) {
    val database = AppDatabase(driver)
    val access = DatabaseAccess(database)
    fun close() = driver.close()
}
