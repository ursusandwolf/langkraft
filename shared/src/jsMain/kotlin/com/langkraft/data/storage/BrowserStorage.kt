@file:JsModule("@langkraft/web-storage")
@file:JsNonModule

package com.langkraft.data.storage

import org.w3c.dom.Worker
import org.w3c.files.Blob
import kotlin.js.Promise

external fun createDatabaseWorker(): Worker
external interface DatabaseClient {
    fun request(action: String, sql: String?, params: Array<Any?>): Promise<dynamic>
    fun close()
    fun setChangeListener(listener: (() -> Unit)?)
    fun publishChanges()
}
external fun createDatabaseClient(): DatabaseClient
external fun beginAudio(contentId: String): Promise<String>
external fun recoverIncompleteAudio(): Promise<Int>
external interface ContentDownloadLease {
    val coordinated: Boolean
    fun release(): Promise<Unit>
}
external fun acquireContentDownload(contentId: String): Promise<ContentDownloadLease?>
external interface PendingAudioEntry {
    val location: String
    val contentId: String
    val recoveryProtocol: String
}
external fun listPendingAudio(): Promise<Array<PendingAudioEntry>>
external fun resolvePendingAudio(location: String, referenced: Boolean): Promise<Boolean>
external fun appendAudio(id: String, bytes: dynamic): Promise<Unit>
external fun commitAudio(id: String, expectedBytes: Double?): Promise<String>
external fun abortAudio(id: String): Promise<Unit>
external fun readAudioBlob(location: String): Promise<Blob>
