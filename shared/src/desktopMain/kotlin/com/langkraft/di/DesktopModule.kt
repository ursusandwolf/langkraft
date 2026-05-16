package com.langkraft.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.db.AppDatabase
import com.langkraft.audio.AudioPlayer
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.audio.FfmpegAudioPlayer
import org.koin.dsl.module
import java.io.File

fun createDesktopModule(isTui: Boolean = false) = module {
    single<AppDatabase> {
        val dbDir = File(System.getProperty("user.home"), ".langkraft").also { it.mkdirs() }
        val dbFile = File(dbDir, "langkraft.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        
        if (isNew) {
            AppDatabase.Schema.create(driver)
        }
        
        AppDatabase(driver)
    }

    if (isTui) {
        single<AudioPlayer> { FfmpegAudioPlayer() }
    } else {
        single<AudioPlayer> { AudioPlayerImpl() }
    }
}
