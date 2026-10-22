package com.langkraft.di

import com.langkraft.audio.AudioPlayer
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.data.audio.AudioStorage
import com.langkraft.data.audio.AudioDownloadCoordinator
import com.langkraft.data.audio.WebAudioDownloadCoordinator
import com.langkraft.data.audio.IndexedDbAudioStorage
import com.langkraft.data.database.DatabaseHandle
import org.koin.dsl.module

fun createWebModule(handle: DatabaseHandle) = module {
    single { handle.access }
    single<AudioStorage> { IndexedDbAudioStorage() }
    single<AudioDownloadCoordinator> { WebAudioDownloadCoordinator() }
    factory<AudioPlayer> { AudioPlayerImpl() }
}
