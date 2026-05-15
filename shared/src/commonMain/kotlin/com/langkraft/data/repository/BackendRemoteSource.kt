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
import io.ktor.http.contentType
import kotlinx.coroutines.delay

class BackendRemoteSource(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : RemoteContentSource {

    override suspend fun fetchFromYouTube(url: String): ImmersionContent {
        val request = IngestRequest(url)
        val response = httpClient.post("$baseUrl/api/ingest") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<IngestResponse>()

        val jobId = response.jobId

        while (true) {
            delay(1000)
            val jobResponse = httpClient.get("$baseUrl/api/ingest/$jobId")

            if (jobResponse.status == HttpStatusCode.NotFound) {
                throw Exception("Job not found")
            }

            val job = jobResponse.body<IngestionJob>()
            if (job.status == ContentProcessingStatus.READY && job.content != null) {
                return job.content
            } else if (job.status == ContentProcessingStatus.ERROR) {
                throw Exception("Ingestion failed: ${job.error}")
            }
        }
    }
}
