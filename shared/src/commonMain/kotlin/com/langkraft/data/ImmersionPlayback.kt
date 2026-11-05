package com.langkraft.data

import com.langkraft.domain.model.DownloadStatus
import com.langkraft.domain.model.ImmersionContent
import io.ktor.http.URLBuilder
import io.ktor.http.Url

/** Resolves offline path or backend-relative audio URL for the player. */
fun ImmersionContent.getPlaybackUrl(baseUrl: Url): String {
    val offlinePath = localAudioPath
    return if (downloadStatus == DownloadStatus.COMPLETED && offlinePath != null) {
        offlinePath
    } else if (audioUrl.startsWith("http")) {
        audioUrl
    } else {
        URLBuilder(baseUrl).apply {
            pathSegments = audioUrl.split("/").filter { it.isNotEmpty() }
        }.buildString()
    }
}
