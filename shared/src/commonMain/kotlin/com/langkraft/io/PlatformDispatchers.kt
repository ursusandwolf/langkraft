package com.langkraft.io

import kotlinx.coroutines.CoroutineDispatcher

/** Dispatcher for repository work: blocking IO on JVM, event loop on JS. */
expect val ioDispatcher: CoroutineDispatcher
