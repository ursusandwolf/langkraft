package com.langkraft.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.http.content.OutgoingContent

/** Keep the reader lease through the response send pipeline (including Range responses). */
fun Route.mediaRoutes(storage: BackendMediaStorage) {
    get("/media/{path...}") { call.respondMedia(storage, head = false) }
    head("/media/{path...}") { call.respondMedia(storage, head = true) }
}

private suspend fun ApplicationCall.respondMedia(storage: BackendMediaStorage, head: Boolean) {
    val lease = storage.acquireMedia(parameters.getAll("path").orEmpty())
    if (lease == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    try {
        if (head) {
            val file = LocalFileContent(lease.file)
            respond(object : OutgoingContent.NoContent() {
                override val contentType = file.contentType
                override val contentLength = file.contentLength
                override val headers = file.headers
            })
        } else respondFile(lease.file)
    } finally { lease.close() }
}
