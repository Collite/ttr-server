// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.checker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture

/**
 * [block] on the IO pool, answered within [timeoutMs] or null. A blocking call cannot be cancelled: the
 * DNS lookup inside `InetSocketAddress(host, port)` and a `Socket.connect` ignore a coroutine's timeout,
 * so `withTimeoutOrNull { blocking() }` waits as long as the resolver does (and a structured child would be
 * waited for too). Here the work runs DETACHED, as a future: the caller gets its answer on time, and the
 * abandoned call finishes, or fails, on its own.
 */
suspend fun <T> blockingWithin(
    timeoutMs: Long,
    block: () -> T,
): T? {
    val future = CompletableFuture.supplyAsync(block, Dispatchers.IO.asExecutor())
    return withTimeoutOrNull(timeoutMs) { future.await() }.also { if (it == null) future.cancel(true) }
}

/** A TCP connect to [host]:[port] — the name resolved and the connection made within [timeoutMs] — then [use]. */
suspend fun <T> tcpConnect(
    host: String,
    port: Int,
    timeoutMs: Long,
    use: (Socket) -> T,
): T? =
    blockingWithin(timeoutMs) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            socket.soTimeout = timeoutMs.toInt()
            use(socket)
        }
    }
