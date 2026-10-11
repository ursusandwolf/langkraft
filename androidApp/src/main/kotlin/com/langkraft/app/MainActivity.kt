package com.langkraft.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.langkraft.audio.AudioPlayerImpl
import com.langkraft.data.*
import com.langkraft.ui.*

class MainActivity : ComponentActivity() {
    private lateinit var services: AppServices
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = createHttpClient()
        services = AppServices(createAndroidDatabase(this), client, AndroidAudioStore(this, client), AudioPlayerImpl(this), "http://10.0.2.2:8080")
        setContent { LangkraftApp(services) }
    }
    override fun onStop() { services.audioPlayer.pause(); super.onStop() }
    override fun onDestroy() { services.close(); super.onDestroy() }
}