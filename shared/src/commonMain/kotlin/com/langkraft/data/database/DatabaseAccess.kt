package com.langkraft.data.database

import com.langkraft.db.AppDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Share one instance across repositories: a Web worker has one SQLite connection. */
class DatabaseAccess(val database: AppDatabase) {
    private val mutex = Mutex()

    suspend fun <T> run(
        dispatcher: CoroutineDispatcher,
        block: suspend AppDatabase.() -> T
    ): T = withContext(dispatcher) { mutex.withLock { database.block() } }
}
