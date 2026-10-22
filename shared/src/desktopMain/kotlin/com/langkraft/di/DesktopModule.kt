package com.langkraft.di

import com.langkraft.audio.AudioPlayer
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.audio.FfmpegAudioPlayer
import org.koin.dsl.module
import java.io.File
import com.langkraft.data.database.DatabaseHandle
import com.langkraft.data.database.createJvmDatabase
import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.audio.JournaledFileAudioStorage
import com.langkraft.data.audio.AudioDownloadCoordinator
import com.langkraft.data.audio.FileAudioDownloadCoordinator
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module

fun createDesktopModule(isTui: Boolean = false): Module {
    val dataDirectory = File(System.getenv("LANGKRAFT_DATA_DIR") ?: File(System.getProperty("user.home"), ".langkraft").path)
    return module {
        single { FileAudioDownloadCoordinator(File(dataDirectory, "download-locks")) }
        single<AudioDownloadCoordinator> { get<FileAudioDownloadCoordinator>() }
        single { JournaledFileAudioStorage(File(dataDirectory, "audio"), get<FileAudioDownloadCoordinator>()) }
        single { runBlocking { createJvmDatabase(File(dataDirectory, "langkraft.db"), get<AudioDownloadCoordinator>(), get<JournaledFileAudioStorage>()) } }
        single { get<DatabaseHandle>().access }
        single<AudioStorage> { get<JournaledFileAudioStorage>() }

        if (isTui) {
            factory<AudioPlayer> { FfmpegAudioPlayer() }
        } else {
            factory<AudioPlayer> { runCatching { AudioPlayerImpl() }.getOrElse { FfmpegAudioPlayer() } }
        }
    }
}
