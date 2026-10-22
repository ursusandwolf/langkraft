package com.langkraft.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.db.AppDatabase
import com.langkraft.audio.AudioPlayer
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.audio.FfmpegAudioPlayer
import org.koin.dsl.module
import java.io.File
import com.langkraft.data.database.DatabaseHandle
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.audio.FileAudioStorage
import kotlinx.coroutines.runBlocking

fun createDesktopModule(isTui: Boolean = false) = module {
    single { runBlocking { createJvmDatabase(File(System.getenv("LANGKRAFT_DATA_DIR") ?: File(System.getProperty("user.home"), ".langkraft").path, "langkraft.db")) } }
    single { get<DatabaseHandle>().access }
    single<AudioStorage> { FileAudioStorage(File(System.getenv("LANGKRAFT_DATA_DIR") ?: File(System.getProperty("user.home"), ".langkraft").path, "audio")) }

    if (isTui) {
        factory<AudioPlayer> { FfmpegAudioPlayer() }
    } else {
        factory<AudioPlayer> { runCatching { AudioPlayerImpl() }.getOrElse { FfmpegAudioPlayer() } }
    }
}
