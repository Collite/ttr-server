// SPDX-License-Identifier: Apache-2.0
package shared.ktor.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MCP_SESSION_ID = "mcp-session-id"

/** The SDK server the transport's factory made for this call, if it made one. */
private val CreatedServer = AttributeKey<Server>("McpSessions.CreatedServer")

/** How long closing one session may take before the sweep gives up on it and moves on. */
private val CLOSE_TIMEOUT = 10.seconds

/**
 * Mounts the MCP Streamable HTTP transport at [path], as the SDK's `mcpStreamableHttp` does, and
 * bounds how long a session lives. Returns the [McpSessionRegistry] that keeps the sessions.
 *
 * **Why.** The SDK keeps a session until the client ends it with a `DELETE` carrying the session's
 * `mcp-session-id`, and otherwise forever: the transport, the SDK [Server] the factory made for it,
 * that server's per-session notification job, and the session's standalone GET stream. A client that
 * opens a session per call and only closes its connection leaves all of them behind on every call.
 * The GET stream's handler stays parked on a connection its peer has already closed (the socket sits
 * in `CLOSE_WAIT`): the server never writes to it, so it never learns, and even the SDK's own close
 * only closes the stream's output, which does not end the handler. Nothing bounds any of this.
 *
 * **What this adds.**
 * - **Idle expiry.** A session that no request has used for [idleTimeout] is closed: its GET stream's
 *   call is cancelled (ending the handler and releasing the socket), then its SDK server is closed,
 *   which cancels the notification job and drops the transport. A request that still carries the old
 *   id is then answered 404, which tells the client to start a new session (MCP Streamable HTTP,
 *   session management). A background sweep
 *   runs every [sweepInterval]; [Duration.INFINITE] turns it off and leaves
 *   [McpSessionRegistry.expireIdle] to the caller.
 * - **In-flight safety.** A session with a POST or DELETE in progress is never expired, however long
 *   that request runs; its idle clock starts again when the request ends. The standalone GET stream
 *   does not count as use: every abandoned session has one, parked for good.
 * - **Explicit end.** On a `DELETE` the session leaves the registry before the SDK answers, so it is
 *   gone by the time the client hears so; its GET stream and SDK server are closed after, as above.
 * - **One log line per removed session**, at INFO, with the reason and the number still open.
 *
 * @param timeSource the clock idleness is measured on. Tests pass a manual one.
 */
fun Application.mcpStreamableHttpWithSessionExpiry(
    path: String = "/mcp",
    idleTimeout: Duration,
    sweepInterval: Duration = (idleTimeout / 4).coerceIn(1.seconds, 30.seconds),
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    block: RoutingContext.() -> Server,
): McpSessionRegistry {
    require(
        idleTimeout.isPositive() && idleTimeout.isFinite(),
    ) { "idleTimeout must be positive and finite: $idleTimeout" }
    require(sweepInterval.isPositive()) { "sweepInterval must be positive: $sweepInterval" }
    val registry = McpSessionRegistry(idleTimeout, timeSource)

    mcpStreamableHttp(path = path) {
        block().also { call.attributes.put(CreatedServer, it) }
    }
    // The same route node the SDK just mounted, so this wraps its GET, POST and DELETE handlers — and
    // runs only for calls the application pipeline let through (a refused bearer never gets here).
    routing {
        route(path) {
            intercept(ApplicationCallPipeline.Plugins) {
                val call = context
                val sessionId = call.request.header(MCP_SESSION_ID)
                val entry = sessionId?.let { registry.entryOf(it) }
                when {
                    sessionId == null -> {
                        proceed()
                        call.attributes.getOrNull(CreatedServer)?.let { registry.adopt(it) }
                    }

                    // Unknown here: the SDK answers it (404), or it is a session adopted a moment from now.
                    entry == null -> proceed()

                    // The standalone stream. The SDK parks its handler until its coroutine is cancelled
                    // (closing the stream's output does not end it), so the stream runs in a child scope
                    // the session keeps, to cancel when the session goes. Only that scope is cancelled,
                    // not the call: the cancellation stops here and the response just ends.
                    call.request.httpMethod == HttpMethod.Get -> {
                        val streamed =
                            try {
                                coroutineScope {
                                    val stream = coroutineContext.job
                                    if (!entry.attach(stream)) return@coroutineScope false
                                    try {
                                        proceed()
                                    } finally {
                                        entry.detach(stream)
                                    }
                                    true
                                }
                            } catch (e: CancellationException) {
                                if (!currentCoroutineContext().isActive) throw e
                                true
                            }
                        if (!streamed) {
                            call.respondText(SESSION_NOT_FOUND, ContentType.Application.Json, HttpStatusCode.NotFound)
                            finish()
                        }
                    }

                    call.request.httpMethod == HttpMethod.Delete -> {
                        registry.endByClient(entry)
                        try {
                            proceed()
                        } finally {
                            registry.closeServer(entry)
                        }
                    }

                    !registry.begin(entry) -> {
                        // The sweep took this session between the lookup and now: answer as for any
                        // ended session, so the client starts a new one.
                        call.respondText(SESSION_NOT_FOUND, ContentType.Application.Json, HttpStatusCode.NotFound)
                        finish()
                    }

                    else ->
                        try {
                            proceed()
                        } finally {
                            registry.end(entry)
                        }
                }
            }
        }
    }

    if (sweepInterval.isFinite()) {
        launch(CoroutineName("mcp-session-sweep")) {
            while (isActive) {
                delay(sweepInterval)
                try {
                    registry.expireIdle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LoggerFactory.getLogger(McpSessionRegistry::class.java).warn("MCP session sweep failed", e)
                }
            }
        }
    }
    return registry
}

private val SESSION_NOT_FOUND: String =
    McpJson.encodeToString(
        JSONRPCError.serializer(),
        JSONRPCError(
            id = null,
            error = RPCError(code = RPCError.ErrorCode.CONNECTION_CLOSED, message = "Session not found"),
        ),
    )

/**
 * The open MCP sessions of one transport mount, by `mcp-session-id`, with when each was last used and
 * whether a request is using it now. See [mcpStreamableHttpWithSessionExpiry].
 */
class McpSessionRegistry internal constructor(
    val idleTimeout: Duration,
    private val timeSource: TimeSource.WithComparableMarks,
) {
    private val log = LoggerFactory.getLogger(McpSessionRegistry::class.java)
    private val entries = ConcurrentHashMap<String, Entry>()

    /** Open sessions. */
    val size: Int get() = entries.size

    operator fun contains(sessionId: String): Boolean = entries.containsKey(sessionId)

    /**
     * Closes every session that is idle past [idleTimeout] and has no request in flight; returns how
     * many it closed. The background sweep calls this; so may a caller that turned the sweep off.
     */
    suspend fun expireIdle(): Int {
        val now = timeSource.markNow()
        val due = entries.values.filter { it.retireIfIdle(now, idleTimeout) }
        for (entry in due) {
            if (entries.remove(entry.id, entry)) {
                log.info("MCP session {} expired after {} idle ({} open)", entry.id, idleTimeout, entries.size)
            }
            closeServer(entry)
        }
        return due.size
    }

    internal fun entryOf(sessionId: String): Entry? = entries[sessionId]

    /**
     * Takes in the SDK server a session-less POST made. An initialize that succeeded gave its transport
     * an id and is registered under it; anything else (a rejected initialize, a POST that was not one)
     * made a server for nothing, and that server is closed rather than left with a live job.
     */
    internal suspend fun adopt(server: Server) {
        val transport =
            server.sessions.values
                .firstOrNull()
                ?.transport as? StreamableHttpServerTransport
        val sessionId = transport?.sessionId
        if (sessionId == null) {
            closeQuietly(server)
            return
        }
        entries[sessionId] = Entry(sessionId, server, timeSource.markNow())
        log.debug("MCP session {} opened ({} open)", sessionId, entries.size)
    }

    internal fun begin(entry: Entry): Boolean = entry.begin(timeSource.markNow())

    internal fun end(entry: Entry) = entry.end(timeSource.markNow())

    internal fun endByClient(entry: Entry) {
        entry.retire()
        if (entries.remove(entry.id, entry)) {
            log.info("MCP session {} ended by the client (DELETE) ({} open)", entry.id, entries.size)
        }
    }

    /**
     * Ends the session's GET streams, then closes its SDK server. The SDK's own close only closes a
     * stream's output, which leaves the handler parked and the connection open (in `CLOSE_WAIT` when
     * the client has gone), so the streams' scopes are cancelled first.
     */
    internal suspend fun closeServer(entry: Entry) {
        entry.cancelStreams()
        closeQuietly(entry.server)
    }

    /**
     * [Server.close] cancels the server's notification jobs and closes its session, which fires the
     * SDK's own clean-up (its session registry, its transport table); it is safe to repeat. A close
     * that hangs must not stall the sweep, so it is bounded.
     */
    private suspend fun closeQuietly(server: Server) {
        try {
            withTimeout(CLOSE_TIMEOUT) { server.close() }
        } catch (e: TimeoutCancellationException) {
            log.warn("closing an MCP session's server took over {}; left to the garbage collector", CLOSE_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("closing an MCP session's server failed: {}", e.toString())
        }
    }

    /** One session. Its state changes under its own lock, which is never held across a suspension. */
    internal class Entry(
        val id: String,
        val server: Server,
        private var lastUsed: ComparableTimeMark,
    ) {
        private var inFlight = 0
        private var retired = false
        private val streams = HashSet<Job>()

        /** Keeps the job of a GET stream's scope; false when the session is already on its way out. */
        @Synchronized
        fun attach(stream: Job): Boolean {
            if (retired) return false
            streams += stream
            return true
        }

        @Synchronized
        fun detach(stream: Job) {
            streams -= stream
        }

        fun cancelStreams() {
            val open = synchronized(this) { streams.toList().also { streams.clear() } }
            open.forEach { it.cancel(CancellationException("MCP session closed")) }
        }

        @Synchronized
        fun begin(now: ComparableTimeMark): Boolean {
            if (retired) return false
            inFlight++
            lastUsed = now
            return true
        }

        @Synchronized
        fun end(now: ComparableTimeMark) {
            if (inFlight > 0) inFlight--
            lastUsed = now
        }

        @Synchronized
        fun retireIfIdle(
            now: ComparableTimeMark,
            idleTimeout: Duration,
        ): Boolean {
            if (retired || inFlight > 0 || now - lastUsed < idleTimeout) return false
            retired = true
            return true
        }

        @Synchronized
        fun retire() {
            retired = true
        }
    }
}
