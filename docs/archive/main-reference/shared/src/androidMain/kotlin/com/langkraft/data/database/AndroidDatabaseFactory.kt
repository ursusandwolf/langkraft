package com.langkraft.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.async.coroutines.synchronous
import com.langkraft.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun createAndroidDatabase(
    context: Context,
    name: String = "langkraft.db"
): DatabaseHandle = withContext(Dispatchers.IO) {
    val driver = AndroidSqliteDriver(
        schema = AppDatabase.Schema.synchronous(),
        context = context.applicationContext,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(AppDatabase.Schema.synchronous()) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }
        }
    )
    try {
        // Force opening/initialization on the IO dispatcher, including onConfigure.
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        DatabaseHandle(driver)
    } catch (exception: Throwable) {
        driver.close()
        throw exception
    }
}
