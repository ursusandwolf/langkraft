package com.langkraft.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.async.coroutines.synchronous
import com.langkraft.db.AppDatabase

fun createAndroidDatabase(context: Context): LocalDatabase {
    val driver = AndroidSqliteDriver(
        AppDatabase.Schema.synchronous(), context.applicationContext, "langkraft.db",
        callback = object : AndroidSqliteDriver.Callback(AppDatabase.Schema.synchronous()) {
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        },
    )
    return LocalDatabase(driver)
}