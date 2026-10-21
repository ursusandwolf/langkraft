package com.langkraft.backend

import java.net.URI

/** Only individual HTTPS YouTube videos can enter the import queue. */
object SourceUrlValidator {
    fun validate(url: String) {
        val uri = try { URI(url) } catch (_: Exception) { throw IngestionException("Enter an HTTPS YouTube video URL") }
        val host = uri.host?.lowercase()
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
            host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be") ||
            uri.port !in listOf(-1, 443)) invalid()
        val path = uri.path.orEmpty().trim('/')
        val video = if (host == "youtu.be") {
            Regex("[A-Za-z0-9_-]+").matches(path)
        } else {
            (path == "watch" && Regex("(^|&)v=[A-Za-z0-9_-]+(&|$)").containsMatchIn(uri.rawQuery.orEmpty())) ||
                Regex("(shorts|live|embed)/[A-Za-z0-9_-]+").matches(path)
        }
        if (!video) invalid()
    }

    private fun invalid(): Nothing = throw IngestionException("Enter an HTTPS YouTube video URL")
}
