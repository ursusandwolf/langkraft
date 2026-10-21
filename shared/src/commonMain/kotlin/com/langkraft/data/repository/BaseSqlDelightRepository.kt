package com.langkraft.data.repository

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

abstract class BaseSqlDelightRepository(
    protected val access: com.langkraft.data.database.DatabaseAccess,
    protected val dispatcher: CoroutineDispatcher = com.langkraft.io.ioDispatcher
) {
    protected fun <T : Any, R : Any> Query<T>.asFlowList(mapper: (T) -> R): Flow<List<R>> {
        return asFlow()
            .map { query -> access.run(dispatcher) { query.awaitAsList() } }
            .map { list -> list.map(mapper) }
    }

    protected fun <T : Any, R : Any> Query<T>.asFlowOne(mapper: (T) -> R): Flow<R> {
        return asFlow()
            .map { query -> access.run(dispatcher) { query.awaitAsOne() } }
            .map(mapper)
    }
}
