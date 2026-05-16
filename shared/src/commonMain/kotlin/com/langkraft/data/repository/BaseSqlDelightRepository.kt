package com.langkraft.data.repository

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

abstract class BaseSqlDelightRepository(
    protected val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    protected fun <T : Any, R : Any> Query<T>.asFlowList(mapper: (T) -> R): Flow<List<R>> {
        return asFlow()
            .mapToList(dispatcher)
            .map { list -> list.map(mapper) }
    }

    protected fun <T : Any, R : Any> Query<T>.asFlowOne(mapper: (T) -> R): Flow<R> {
        return asFlow()
            .mapToOne(dispatcher)
            .map(mapper)
    }
}
