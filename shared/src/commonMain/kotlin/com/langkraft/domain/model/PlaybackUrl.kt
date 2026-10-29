package com.langkraft.domain.model

fun ImmersionContent.getPlaybackUrl(baseUrl: io.ktor.http.Url): String {
    val localPath = localAudioPath
    return if (downloadStatus == DownloadStatus.COMPLETED && localPath != null) {
        localPath
    } else {
        if (audioUrl.startsWith("http")) {
            audioUrl
        } else {
            io.ktor.http.URLBuilder(baseUrl).apply {
                pathSegments = audioUrl.split("/").filter { it.isNotEmpty() }
            }.buildString()
        }
    }
}
