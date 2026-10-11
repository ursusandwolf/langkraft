package com.langkraft.data

import com.langkraft.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun interface ContentRemote { suspend fun ingest(url: String): ImmersionContent }
interface AudioStore {
    suspend fun download(contentId: String, audioUrl: String): String
    suspend fun resolve(path: String): String = path
    fun close() {}
}

fun createHttpClient() = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 240_000; connectTimeoutMillis = 30_000; socketTimeoutMillis = 240_000 }
}

class KtorContentRemote(private val client: HttpClient, baseUrl: String) : ContentRemote {
    private val base = baseUrl.trimEnd('/')
    init { require(Url(base).protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS)) { "Use an HTTP backend URL" } }
    override suspend fun ingest(url: String): ImmersionContent {
        val response = client.post("$base/api/ingest") {
            contentType(ContentType.Application.Json)
            setBody(IngestRequest(url))
        }
        if (!response.status.isSuccess()) {
            val error = runCatching { Json.decodeFromString<ApiError>(response.bodyAsText()).message }.getOrDefault("Import failed (${response.status.value})")
            throw IllegalStateException(error)
        }
        val content = response.body<ImmersionContent>()
        val media = content.audioUrl
        require(media.startsWith("/api/media/") && !media.contains("..")) { "Invalid media URL from backend" }
        return content.copy(audioUrl = base + media, localAudioPath = null)
    }
}