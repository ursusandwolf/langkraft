package com.langkraft.di

import com.langkraft.audio.AudioPlayer
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.audio.FfmpegAudioPlayer
import org.koin.dsl.module
import java.io.File
import com.langkraft.data.database.DatabaseHandle
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.audio.FileAudioStorage
import com.langkraft.data.audio.AudioDownloadCoordinator
import com.langkraft.data.audio.FileAudioDownloadCoordinator
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module

fun createDesktopModule(isTui: Boolean = false): Module {
    val dataDirectory = File(System.getenv("LANGKRAFT_DATA_DIR") ?: File(System.getProperty("user.home"), ".langkraft").path)
    return module {
        single<AudioDownloadCoordinator> { FileAudioDownloadCoordinator(File(dataDirectory, "download-locks")) }
        single { runBlocking { createJvmDatabase(File(dataDirectory, "langkraft.db"), get<AudioDownloadCoordinator>()) } }
        single { get<DatabaseHandle>().access }
        single<AudioStorage> { FileAudioStorage(File(dataDirectory, "audio")) }

        if (isTui) {
            factory<AudioPlayer> { FfmpegAudioPlayer() }
        } else {
            factory<AudioPlayer> { runCatching { AudioPlayerImpl() }.getOrElse { FfmpegAudioPlayer() } }
        }
    }
}
