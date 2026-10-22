package com.langkraft.data.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.langkraft.data.storage.DatabaseClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.withContext
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/**
 * Async SQLDelight adapter. The client owns message listeners; transaction cleanup
 * finishes even if the calling coroutine is cancelled while waiting for SQLite.
 */
@androidx.compose.runtime.NoLiveLiterals
internal class BrowserSqlDriver(private val client: DatabaseClient) : SqlDriver {
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()
    private var activeTransaction: Transacter.Transaction? = null
    private var externalChangePending = false
    private var publicationPending = false
    private var closed = false

    init {
        client.setChangeListener {
            if (!closed) {
                if (activeTransaction != null) externalChangePending = true
                else invalidateQueries()
            }
        }
    }

    // Notify only: repository Flow reads still acquire the shared DatabaseAccess mutex.
    private fun invalidateQueries() {
        listeners.values.flatMap { it }.toSet().forEach { it.queryResultsChanged() }
    }

    override fun <R> executeQuery(
        identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<R> {
        val values = bind(parameters, binders)
        return QueryResult.AsyncValue {
            val rows = client.request("exec", sql, values).await()
            mapper(BrowserCursor(rows)).await()
        }
    }

    override fun execute(
        identifier: Int?, sql: String, parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> {
        val values = bind(parameters, binders)
        return QueryResult.AsyncValue {
            val rows = client.request("exec", sql, values).await()
            if ((rows.length as Int) == 0) 0L else (rows[0][0] as Double).toLong()
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue {
        val parent = activeTransaction
        if (parent == null) {
            try {
                client.request("begin_transaction", null, emptyArray()).await()
            } catch (error: Throwable) {
                // A cancelled await may still have dispatched BEGIN. Queue a rollback
                // behind it before releasing access to this connection.
                withContext(NonCancellable) {
                    try { client.request("rollback_transaction", null, emptyArray()).await() }
                    catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
                throw error
            }
        }
        BrowserTransaction(parent).also { activeTransaction = it }
    }

    override fun currentTransaction(): Transacter.Transaction? = activeTransaction

    private inner class BrowserTransaction(
        override val enclosingTransaction: Transacter.Transaction?
    ) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.AsyncValue {
            withContext(NonCancellable) {
                var committed = false
                try {
                    if (enclosingTransaction == null) {
                        client.request(if (successful) "end_transaction" else "rollback_transaction",
                            null, emptyArray()).await()
                        committed = successful
                    }
                } catch (error: Throwable) {
                    if (successful && enclosingTransaction == null) {
                        try { client.request("rollback_transaction", null, emptyArray()).await() }
                        catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    }
                    throw error
                } finally {
                    activeTransaction = enclosingTransaction
                    if (enclosingTransaction == null) {
                        if (committed && publicationPending) client.publishChanges()
                        publicationPending = false
                        if (externalChangePending) {
                            externalChangePending = false
                            invalidateQueries()
                        }
                    }
                }
            }
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners.getOrPut(it) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners[it]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        if (closed) return
        queryKeys.flatMap { listeners[it].orEmpty() }.toSet().forEach { it.queryResultsChanged() }
        if (activeTransaction == null) client.publishChanges()
        else publicationPending = true
    }

    override fun close() {
        if (closed) return
        closed = true
        client.setChangeListener(null)
        listeners.clear()
        client.close()
    }

    private fun bind(count: Int, binder: (SqlPreparedStatement.() -> Unit)?): Array<Any?> {
        val values = arrayOfNulls<Any>(count)
        binder?.invoke(object : SqlPreparedStatement {
            override fun bindBytes(index: Int, bytes: ByteArray?) { values[index] = bytes }
            override fun bindLong(index: Int, long: Long?) { values[index] = long?.toDouble() }
            override fun bindDouble(index: Int, double: Double?) { values[index] = double }
            override fun bindString(index: Int, string: String?) { values[index] = string }
            override fun bindBoolean(index: Int, boolean: Boolean?) {
                values[index] = boolean?.let { if (it) 1 else 0 }
            }
        })
        return values
    }
}

@androidx.compose.runtime.NoLiveLiterals
private class BrowserCursor(private val rows: dynamic) : SqlCursor {
    private var index = -1
    override fun next(): QueryResult.Value<Boolean> = QueryResult.Value(++index < (rows.length as Int))
    override fun getString(index: Int): String? = rows[this.index][index] as String?
    override fun getLong(index: Int): Long? = (rows[this.index][index] as Double?)?.toLong()
    override fun getDouble(index: Int): Double? = rows[this.index][index] as Double?
    override fun getBoolean(index: Int): Boolean? = (rows[this.index][index] as Double?)?.let { it != 0.0 }
    override fun getBytes(index: Int): ByteArray? {
        val bytes = rows[this.index][index] as Uint8Array? ?: return null
        return Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength).unsafeCast<ByteArray>()
    }
}
