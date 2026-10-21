package com.langkraft.data.database

import app.cash.sqldelight.async.coroutines.awaitQuery
import app.cash.sqldelight.db.SqlDriver
import com.langkraft.data.storage.createDatabaseClient
import com.langkraft.db.AppDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun createWebDatabase(): DatabaseHandle {
    val driver: SqlDriver = BrowserSqlDriver(createDatabaseClient())
    try {
        val version = driver.awaitQuery(null, "PRAGMA user_version", { cursor ->
            cursor.next().await()
            cursor.getLong(0) ?: 0L
        }, 0)
        require(version <= AppDatabase.Schema.version) { "Database was created by a newer app version" }
        // Finish schema changes before exposing the connection or closing its worker.
        withContext(NonCancellable) {
            AppDatabase(driver).transaction {
                if (version == 0L) AppDatabase.Schema.create(driver).await()
                else if (version < AppDatabase.Schema.version) {
                    AppDatabase.Schema.migrate(driver, version, AppDatabase.Schema.version).await()
                }
                driver.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version}", 0).await()
            }
        }
        return DatabaseHandle(driver)
    } catch (error: Throwable) {
        driver.close()
        throw error
    }
}
