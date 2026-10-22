package com.langkraft.backend

import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.routing.RoutingApplicationCall
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope

/** Ktor 2 does not cancel a running handler when an HTTP/1 client disconnects. */
internal suspend fun <T> ApplicationCall.cancelOnDisconnect(block: suspend () -> T): T = coroutineScope {
    val engineCall = (this@cancelOnDisconnect as? RoutingApplicationCall)?.engineCall ?: this@cancelOnDisconnect
    val closed = (engineCall as? NettyApplicationCall)?.context?.channel()?.closeFuture()
    val listener = ChannelFutureListener { cancel("Import client disconnected") }
    closed?.addListener(listener)
    try { block() } finally { closed?.removeListener(listener) }
}
