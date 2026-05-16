package com.langkraft.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.db.AppDatabase
import org.koin.dsl.module
import java.io.File

val desktopModule = module {
    single<AppDatabase> {
        val dbDir = File(System.getProperty("user.home"), ".langkraft").also { it.mkdirs() }
        val dbFile = File(dbDir, "langkraft.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        
        // Check if database needs schema creation
        // We can check if the file is empty or use PRAGMA user_version
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        
        if (isNew) {
            AppDatabase.Schema.create(driver)
        }
        
        AppDatabase(driver)
    }
}
