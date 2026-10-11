package com.langkraft.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.db.AppDatabase

suspend fun createJvmDatabase(url: String = JdbcSqliteDriver.IN_MEMORY): LocalDatabase {
    val driver = JdbcSqliteDriver(url)
    driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
    AppDatabase.Schema.create(driver).await()
    return LocalDatabase(driver)
}