package com.langkraft.backend

import com.langkraft.domain.model.SyncRequest
import com.langkraft.domain.model.SyncLimits
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException

/** HTTP limits are independent of provider configuration and checked before downstream work. */
object ApiLimits {
    const val AUTH_BODY_BYTES = 8 * 1024
    const val INGEST_BODY_BYTES = 8 * 1024
    const val AI_BODY_BYTES = 96 * 1024
    const val SYNC_BODY_BYTES = SyncLimits.MAX_BODY_BYTES
    const val SYNC_BATCH_SIZE = SyncLimits.MAX_BATCH_SIZE
    const val AI_TEXT_CHARS = 12_000
    const val AI_WORD_CHARS = 256
}

class RequestValidationException : RuntimeException("Invalid request parameters")
class InvalidRequestBodyException : RuntimeException("Invalid request body")
class RequestBodyTooLargeException : RuntimeException("Request body exceeds the allowed limit")
class UnsupportedRequestTypeException : RuntimeException("Use application/json with UTF-8 encoding")

fun validateRequest(valid: Boolean) {
    if (!valid) throw RequestValidationException()
}

/** Read at most limit + 1 bytes, even for chunked bodies and misleading Content-Length headers. */
internal suspend inline fun <reified T> ApplicationCall.receiveLimitedJson(limit: Int): T {
    val lengths = request.headers.getAll(HttpHeaders.ContentLength)
    if (lengths != null) {
        val length = lengths.singleOrNull()?.toLongOrNull() ?: throw RequestValidationException()
        validateRequest(length >= 0)
        if (length > limit) throw RequestBodyTooLargeException()
    }
    val type = try { request.contentType() }
        catch (_: BadContentTypeFormatException) { throw InvalidRequestBodyException() }
    if (!type.match(ContentType.Application.Json) ||
        (type.parameter("charset")?.let { !it.equals("utf-8", ignoreCase = true) } == true) ||
        request.headers[HttpHeaders.ContentEncoding]?.let { !it.equals("identity", ignoreCase = true) } == true) {
        throw UnsupportedRequestTypeException()
    }
    val channel = receiveChannel()
    val bytes = ByteArray(limit + 1)
    var size = 0
    while (true) {
        val read = channel.readAvailable(bytes, size, bytes.size - size)
        if (read < 0) break
        size += read
        if (size > limit) throw RequestBodyTooLargeException()
    }
    return try {
        Json.decodeFromString<T>(bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true))
    } catch (_: SerializationException) {
        throw InvalidRequestBodyException()
    } catch (_: CharacterCodingException) {
        throw InvalidRequestBodyException()
    }
}

fun validateAiText(text: String) = validateRequest(text.isNotBlank() && text.length <= ApiLimits.AI_TEXT_CHARS)

fun validateSync(request: SyncRequest) {
    validateRequest(request.lastSyncTimestamp >= 0 && request.clientChanges.size <= ApiLimits.SYNC_BATCH_SIZE)
    request.clientChanges.forEach { entry ->
        val word = entry.word
        validateRequest(entry.changeType in setOf("UPSERT", "DELETE"))
        validateRequest(word.id.isNotBlank() && word.id.length <= 36 && word.lastUpdated >= 0)
        // Client tombstones intentionally contain only ID and revision; their word/context are empty.
        if (entry.changeType == "UPSERT") {
            validateRequest(word.word.isNotBlank() && word.word.length <= 255)
            validateRequest((word.lemma?.length ?: 0) <= 255 && (word.translation?.length ?: 0) <= 1024)
            validateRequest(word.contextSentence.length <= ApiLimits.AI_TEXT_CHARS)
            validateRequest((word.contentId?.length ?: 0) <= 36 && (word.subtitleLineId?.length ?: 0) <= 36)
            validateRequest(word.tags.size <= 100 && word.tags.all { it.length <= 255 })
            validateRequest(word.addedAt >= 0 && word.nextReviewMs >= 0 && word.intervalDays >= 0 &&
                word.lapseCount >= 0 && word.easeFactor.isFinite() && word.easeFactor > 0)
        }
    }
}
