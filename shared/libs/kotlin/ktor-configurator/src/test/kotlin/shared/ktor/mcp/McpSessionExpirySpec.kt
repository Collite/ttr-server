// SPDX-License-Identifier: Apache-2.0
package shared.ktor.mcp

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * ttr-server#201 — an MCP door must bound its sessions on its own: any client may vanish without
 * ending its session. These run the real stack (a CIO server, the SDK's Streamable HTTP transport,
 * SDK clients over HTTP) with the session clock under the test's control, so "an hour passes" is a
 * line, not a wait.
 */
class McpSessionExpirySpec :
    StringSpec({
        "sessions a client abandons expire once idle past the timeout: removed, jobs cancelled, streams released" {
            Door(idleTimeout = 10.minutes).use { door ->
                val ids = (1..12).map { door.openAndAbandon() }

                door.sessions.size shouldBe 12
                door.servers.size shouldBe 12
                door.servers.forEach { activeNotificationJobs(it) shouldBe 1 }
                // Each abandoned session still holds its standalone GET stream, parked on a connection
                // the client has already closed: the server never notices on its own.
                eventually(5.seconds) { door.openStreams.get() shouldBeGreaterThan 0 }

                door.time += 9.minutes
                door.sessions.expireIdle() shouldBe 0
                door.sessions.size shouldBe 12

                door.time += 2.minutes
                door.sessions.expireIdle() shouldBe 12
                door.sessions.size shouldBe 0
                door.servers.forEach { server ->
                    server.sessions.shouldBeEmpty()
                    notificationScopeJob(server).isCancelled shouldBe true
                    activeNotificationJobs(server) shouldBe 0
                }
                eventually(5.seconds) { door.openStreams.get() shouldBe 0 }
                // The transport is gone too: the old id is "session not found", which tells a client
                // that comes back to start a new session (MCP Streamable HTTP, session management).
                ids.forEach { door.postWithSession(it) shouldBe 404 }
            }
        }

        "a DELETE ends the session at once" {
            Door(idleTimeout = 10.minutes).use { door ->
                val http = HttpClient(ClientCIO) { install(SSE) }
                val transport = StreamableHttpClientTransport(client = http, url = door.url)
                val client = Client(Implementation(name = "deleter", version = "0"))
                try {
                    client.connect(transport)
                    client.callTool(name = "echo", arguments = emptyMap())
                    val id = transport.sessionId!!
                    door.sessions.size shouldBe 1

                    transport.terminateSession()

                    (id in door.sessions) shouldBe false
                    door.sessions.size shouldBe 0
                    door.servers
                        .single()
                        .sessions
                        .shouldBeEmpty()
                    eventually(5.seconds) { notificationScopeJob(door.servers.single()).isCancelled shouldBe true }
                    // The SDK's own DELETE closes the stream's output but leaves its handler parked.
                    eventually(5.seconds) { door.openStreams.get() shouldBe 0 }
                    door.postWithSession(id) shouldBe 404
                    // Nothing is left for the sweep to find.
                    door.time += 1.minutes + door.sessions.idleTimeout
                    door.sessions.expireIdle() shouldBe 0
                } finally {
                    client.close()
                    http.close()
                }
            }
        }

        "a session with a request in flight is never expired, however long the request runs" {
            Door(idleTimeout = 10.minutes).use { door ->
                val http = HttpClient(ClientCIO) { install(SSE) }
                val client = Client(Implementation(name = "slow", version = "0"))
                try {
                    client.connect(StreamableHttpClientTransport(client = http, url = door.url))
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    door.hold = entered to release
                    coroutineScope {
                        val call = async { client.callTool(name = "echo", arguments = emptyMap()) }
                        entered.await()

                        door.time += 1.minutes * 60
                        door.sessions.expireIdle() shouldBe 0
                        door.sessions.size shouldBe 1

                        release.complete(Unit)
                        (call.await()?.content?.single() as TextContent).text shouldBe "ok"
                    }
                    // The idle clock starts when the request ends, not when it began.
                    door.sessions.expireIdle() shouldBe 0
                    door.time += 9.minutes
                    door.sessions.expireIdle() shouldBe 0
                    door.time += 2.minutes
                    door.sessions.expireIdle() shouldBe 1
                } finally {
                    client.close()
                    http.close()
                }
            }
        }

        "a session in regular use survives any number of timeouts' worth of time" {
            Door(idleTimeout = 10.minutes).use { door ->
                val http = HttpClient(ClientCIO) { install(SSE) }
                val client = Client(Implementation(name = "steady", version = "0"))
                try {
                    client.connect(StreamableHttpClientTransport(client = http, url = door.url))
                    repeat(12) {
                        door.time += 5.minutes
                        door.sessions.expireIdle() shouldBe 0
                        (client.callTool(name = "echo", arguments = emptyMap())?.content?.single() as TextContent)
                            .text shouldBe "ok"
                    }
                    door.sessions.size shouldBe 1
                } finally {
                    client.close()
                    http.close()
                }
            }
        }

        "the session table stays bounded by the sessions active within one timeout" {
            Door(idleTimeout = 10.minutes).use { door ->
                // A client that opens a session per call, at one call a minute, for 200 minutes.
                var peak = 0
                repeat(200) {
                    door.openAndAbandon()
                    door.time += 1.minutes
                    door.sessions.expireIdle()
                    peak = maxOf(peak, door.sessions.size)
                }
                peak shouldBeLessThanOrEqual 10
                eventually(5.seconds) { door.openStreams.get() shouldBeLessThanOrEqual 10 }
            }
        }

        "a closed session's stream ends quietly: nothing reaches the application's exception handling" {
            val escaped = CopyOnWriteArrayList<Throwable>()
            Door(idleTimeout = 10.minutes, configure = {
                install(StatusPages) { exception<Throwable> { _, cause -> escaped += cause } }
            }).use { door ->
                repeat(3) { door.openAndAbandon() }
                eventually(5.seconds) { door.openStreams.get() shouldBeGreaterThan 0 }
                door.time += 11.minutes
                door.sessions.expireIdle() shouldBe 3
                eventually(5.seconds) { door.openStreams.get() shouldBe 0 }

                val http = HttpClient(ClientCIO) { install(SSE) }
                val transport = StreamableHttpClientTransport(client = http, url = door.url)
                val client = Client(Implementation(name = "deleter", version = "0"))
                try {
                    client.connect(transport)
                    client.callTool(name = "echo", arguments = emptyMap())
                    eventually(5.seconds) { door.openStreams.get() shouldBe 1 }
                    transport.terminateSession()
                    eventually(5.seconds) { door.openStreams.get() shouldBe 0 }
                } finally {
                    client.close()
                    http.close()
                }
                escaped.shouldBeEmpty()
            }
        }

        "the background sweep expires idle sessions without being asked" {
            Door(
                idleTimeout = 3.seconds,
                sweepInterval = 100.milliseconds,
                realTime = true,
            ).use { door ->
                repeat(3) { door.openAndAbandon() }
                door.sessions.size shouldBe 3
                eventually(15.seconds) { door.sessions.size shouldBe 0 }
                eventually(5.seconds) { door.openStreams.get() shouldBe 0 }
            }
        }
    })

/** A thread-safe manual clock: the server reads it on its own threads while the test advances it. */
private class ManualTimeSource : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    private val nanos = AtomicLong(0)

    override fun read(): Long = nanos.get()

    operator fun plusAssign(duration: Duration) {
        nanos.addAndGet(duration.inWholeNanoseconds)
    }
}

/** One MCP door on a real CIO server, mounted with [mcpStreamableHttpWithSessionExpiry]. */
private class Door(
    idleTimeout: Duration,
    sweepInterval: Duration = Duration.INFINITE,
    realTime: Boolean = false,
    configure: Application.() -> Unit = {},
) : AutoCloseable {
    val time = ManualTimeSource()
    val servers = CopyOnWriteArrayList<Server>()

    /** Server-side GET streams that are still open. */
    val openStreams = AtomicInteger()

    /** When set, the next tool call signals `first` on entry and waits for `second`. */
    @Volatile
    var hold: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null

    lateinit var sessions: McpSessionRegistry

    private val engine =
        embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            configure()
            intercept(ApplicationCallPipeline.Plugins) {
                if (context.request.local.method != HttpMethod.Get) return@intercept
                openStreams.incrementAndGet()
                try {
                    proceed()
                } finally {
                    openStreams.decrementAndGet()
                }
            }
            sessions =
                if (realTime) {
                    mcpStreamableHttpWithSessionExpiry(idleTimeout = idleTimeout, sweepInterval = sweepInterval) {
                        newServer()
                    }
                } else {
                    mcpStreamableHttpWithSessionExpiry(
                        idleTimeout = idleTimeout,
                        sweepInterval = sweepInterval,
                        timeSource = time,
                    ) { newServer() }
                }
        }.start(wait = false)

    private fun newServer(): Server =
        Server(
            serverInfo = Implementation(name = "door", version = "0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                ),
        ).apply {
            addTool(name = "echo", description = "answers ok") {
                hold?.let { (entered, release) ->
                    hold = null
                    entered.complete(Unit)
                    release.await()
                }
                CallToolResult(content = listOf(TextContent(text = "ok")))
            }
        }.also { servers += it }

    val url: String =
        runBlocking {
            "http://127.0.0.1:${engine.engine.resolvedConnectors().first().port}/mcp"
        }

    /** What a client that opens a session per call does: connect, call, close — and never end the session. */
    suspend fun openAndAbandon(): String {
        val http = HttpClient(ClientCIO) { install(SSE) }
        val transport = StreamableHttpClientTransport(client = http, url = url)
        val client = Client(Implementation(name = "per-call", version = "0"))
        try {
            client.connect(transport)
            client.callTool(name = "echo", arguments = emptyMap())
            return transport.sessionId!!
        } finally {
            client.close()
            http.close()
        }
    }

    /** A raw `tools/list` on [sessionId]; returns the HTTP status. */
    suspend fun postWithSession(sessionId: String): Int {
        val http = HttpClient(ClientCIO)
        try {
            return http
                .post(url) {
                    header("mcp-session-id", sessionId)
                    header("Accept", "application/json, text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                }.status.value
        } finally {
            http.close()
        }
    }

    override fun close() = engine.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
}

/**
 * The job of the scope each SDK [Server] runs its per-session notification jobs in. The SDK keeps it
 * private, so this reads the field; an SDK upgrade that renames it fails these tests loudly here
 * rather than passing them silently.
 */
private fun notificationScopeJob(server: Server): Job {
    val service =
        Server::class.java
            .getDeclaredField("notificationService")
            .apply { isAccessible = true }
            .get(server)
    val scope =
        service.javaClass
            .getDeclaredField("notificationScope")
            .apply { isAccessible = true }
            .get(service) as CoroutineScope
    return scope.coroutineContext.job
}

private fun activeNotificationJobs(server: Server): Int = notificationScopeJob(server).children.count { it.isActive }
