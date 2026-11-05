package com.langkraft.data.repository

import com.langkraft.domain.model.*
import com.langkraft.domain.repository.RemoteContentSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendEncodedPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class BackendRemoteSource(
    private val httpClient: HttpClient,
    private val baseUrl: io.ktor.http.Url,
    private val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS
) : RemoteContentSource {

    override suspend fun fetchFromYouTube(url: String): ImmersionContent {
        val request = IngestRequest(url)
        val startResponse = httpClient.post(baseUrl) {
            url { appendEncodedPathSegments("api", "ingest") }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        check(startResponse.status.isSuccess()) { "Import service returned HTTP ${startResponse.status.value}" }
        val response = startResponse.body<IngestResponse>()

        val jobId = response.jobId

        try {
            return withTimeout(pollTimeoutMs) {
                while (true) {
                    delay(1_000)
                    val jobResponse = httpClient.get(baseUrl) {
                        url { appendEncodedPathSegments("api", "ingest", jobId) }
                    }

                    if (jobResponse.status == HttpStatusCode.NotFound) {
                        throw Exception("Job not found")
                    }
                    check(jobResponse.status.isSuccess()) { "Import status returned HTTP ${jobResponse.status.value}" }

                    val job = jobResponse.body<IngestionJob>()
                    val readyContent = job.content
                    if (job.status == ContentProcessingStatus.READY && readyContent != null) {
                        return@withTimeout readyContent
                    } else if (job.status == ContentProcessingStatus.ERROR) {
                        throw Exception("Ingestion failed: ${job.error}")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            throw Exception("Import timed out after ${pollTimeoutMs / 1000}s (jobId=$jobId)", e)
        }
    }

    private companion object {
        // Backend yt-dlp 600s + waveform 120s, with margin for queue wait.
        const val DEFAULT_POLL_TIMEOUT_MS = 900_000L
    }
}
