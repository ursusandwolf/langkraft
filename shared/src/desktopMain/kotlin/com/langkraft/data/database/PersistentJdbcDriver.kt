package com.langkraft.data.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import app.cash.sqldelight.driver.jdbc.ConnectionManager
import java.sql.Connection
import java.sql.DriverManager

/** One connection owned by DatabaseHandle; DatabaseAccess serializes all repository operations. */
internal class PersistentJdbcDriver(url: String) : JdbcDriver() {
    private val connection = DriverManager.getConnection(url)
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()
    override var transaction: ConnectionManager.Transaction? = null
    override fun getConnection(): Connection = connection
    override fun closeConnection(connection: Connection) = Unit
    override fun close() { synchronized(listeners) { listeners.clear() }; connection.close() }
    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners.getOrPut(it) { mutableSetOf() }.add(listener) } }
    }
    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.remove(listener) } }
    }
    override fun notifyListeners(vararg queryKeys: String) {
        val snapshot = synchronized(listeners) { queryKeys.flatMap { listeners[it].orEmpty() }.toSet() }
        snapshot.forEach { it.queryResultsChanged() }
    }
}
