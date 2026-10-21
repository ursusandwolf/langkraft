package com.langkraft.data.remote

import com.langkraft.data.audio.AudioStorage
import com.langkraft.domain.model.ImmersionContent
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ContentApiException(val statusCode: Int) : Exception("Content service returned HTTP $statusCode")
class UnexpectedAudioContentTypeException(val mediaType: String) :
    Exception("Expected audio but received $mediaType")

class ContentApi(
    private val client: HttpClient,
    baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val baseUrl = baseUrl.trimEnd('/')

    init {
        require(Url(baseUrl).protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS)) {
            "The backend URL must use HTTP or HTTPS"
        }
    }

    suspend fun ingest(youtubeUrl: String): ImmersionContent {
        val response = client.post("$baseUrl/api/ingest") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(IngestRequest(youtubeUrl)))
        }
        checkResponse(response)
        val content = json.decodeFromString<ImmersionContent>(response.bodyAsText())
        return content.copy(audioUrl = resolve(content.audioUrl), localAudioPath = null)
    }

    suspend fun download(content: ImmersionContent, storage: AudioStorage): String =
        client.prepareGet(resolve(content.audioUrl)).execute { response ->
            checkResponse(response)
            checkAudioContentType(response)
            storage.store(content.id, response.bodyAsChannel(), response.contentLength())
        }

    private fun checkAudioContentType(response: HttpResponse) {
        // Missing/generic binary MIME remains compatible with media servers. This
        // guard rejects explicit non-audio responses; it does not validate codecs.
        val type = response.contentType() ?: return
        if (!type.contentType.equals("audio", ignoreCase = true) &&
            !type.match(ContentType.Application.OctetStream) &&
            !type.match(ContentType("application", "ogg"))) {
            throw UnexpectedAudioContentTypeException(type.toString())
        }
    }

    private fun resolve(url: String): String = URLBuilder(baseUrl).takeFrom(url).buildString()

    private fun checkResponse(response: HttpResponse) {
        if (!response.status.isSuccess()) throw ContentApiException(response.status.value)
    }

    @Serializable
    private data class IngestRequest(val url: String)
}
