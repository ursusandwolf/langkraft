package com.langkraft.data.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

suspend fun createJvmDatabase(file: File): DatabaseHandle = withContext(Dispatchers.IO) {
    file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties().apply {
        setProperty("foreign_keys", "true")
    })
    try {
        val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        }, 0).value
        require(version <= AppDatabase.Schema.version) { "Database was created by a newer app version" }
        AppDatabase(driver).transaction {
            if (version == 0L) AppDatabase.Schema.create(driver).await()
            else if (version < AppDatabase.Schema.version) {
                AppDatabase.Schema.migrate(driver, version, AppDatabase.Schema.version).await()
            }
            driver.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version}", 0)
        }
        DatabaseHandle(driver)
    } catch (exception: Throwable) {
        driver.close()
        throw exception
    }
}
