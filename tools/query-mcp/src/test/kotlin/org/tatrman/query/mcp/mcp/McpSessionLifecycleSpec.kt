// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.mcp

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.mcp.identity.UserIdentity
import org.tatrman.query.mcp.QueryMcpConfig
import shared.ktor.mcp.McpSessionRegistry
import shared.ktor.mcp.installMcpRequestHeaders
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * ttr-server#201 — query-mcp bounds its MCP sessions: [installQueryMcp] mounts the transport with
 * idle expiry at `query-mcp.mcp.session-idle-timeout-seconds`, and a `DELETE` ends a session at once.
 * The mechanism itself is specified in `ktor-configurator`'s `McpSessionExpirySpec`; this pins the
 * wiring and the config key.
 */
class McpSessionLifecycleSpec :
    StringSpec({
        "the idle timeout is query-mcp.mcp.session-idle-timeout-seconds, 600 s by default, and must be positive" {
            QueryMcpConfig.load(ConfigFactory.load()).sessionIdleTimeoutSeconds shouldBe 600L
            QueryMcpConfig
                .load(
                    ConfigFactory
                        .parseString("query-mcp.mcp.session-idle-timeout-seconds = 90")
                        .withFallback(ConfigFactory.load()),
                ).sessionIdleTimeoutSeconds shouldBe 90L
            shouldThrow<IllegalArgumentException> {
                QueryMcpConfig.load(
                    ConfigFactory
                        .parseString("query-mcp.mcp.session-idle-timeout-seconds = 0")
                        .withFallback(ConfigFactory.load()),
                )
            }
        }

        "sessions a client abandons are closed after the configured idle timeout" {
            QueryMcpDoor(idleTimeoutSeconds = 60).use { door ->
                val ids = (1..5).map { door.openAndAbandon() }
                door.sessions.size shouldBe 5

                door.time += 59.seconds
                door.sessions.expireIdle() shouldBe 0

                door.time += 2.seconds
                door.sessions.expireIdle() shouldBe 5
                door.sessions.size shouldBe 0
                ids.forEach { door.postWithSession(it) shouldBe 404 }
            }
        }

        "a DELETE ends a session at once" {
            QueryMcpDoor(idleTimeoutSeconds = 60).use { door ->
                val http = HttpClient(ClientCIO) { install(SSE) }
                val transport = StreamableHttpClientTransport(client = http, url = door.url)
                val client = Client(Implementation(name = "deleter", version = "0"))
                try {
                    client.connect(transport)
                    client.callTool(name = "query", arguments = emptyMap())
                    val id = transport.sessionId!!
                    door.sessions.size shouldBe 1

                    transport.terminateSession()

                    door.sessions.size shouldBe 0
                    door.postWithSession(id) shouldBe 404
                } finally {
                    client.close()
                    http.close()
                }
            }
        }
    })

private class ManualTimeSource : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    private val nanos = AtomicLong(0)

    override fun read(): Long = nanos.get()

    operator fun plusAssign(duration: Duration) {
        nanos.addAndGet(duration.inWholeNanoseconds)
    }
}

/** query-mcp's transport, as [installQueryMcp] mounts it, on a real CIO server with a stub `query` tool. */
private class QueryMcpDoor(
    idleTimeoutSeconds: Long,
) : AutoCloseable {
    val time = ManualTimeSource()
    lateinit var sessions: McpSessionRegistry

    private val cfg =
        QueryMcpConfig(
            serverPort = 0,
            mcpTransport = "streamable-http",
            mcpPath = "/mcp",
            upstream =
                QueryMcpConfig.Upstream(
                    queryRunner = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                    translator = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                    validator = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                    metadata = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                ),
            limits = QueryMcpConfig.Limits(500, 5000, 120, 32 * 1024 * 1024),
            security = QueryMcpConfig.Security(requireIdentity = false),
            toolTimeoutsMs = mapOf("query" to 120_000L),
            sessionIdleTimeoutSeconds = idleTimeoutSeconds,
        )

    private val stubQuery =
        object : McpTool {
            override val name = "query"
            override val description = "stub"
            override val inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList())

            override suspend fun execute(
                request: CallToolRequest,
                identity: UserIdentity?,
            ): CallToolResult = CallToolResult(content = listOf(TextContent(text = "ok")))
        }

    private val engine =
        embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            installMcpRequestHeaders()
            sessions = installQueryMcp(cfg, ToolRegistry(listOf(stubQuery)), timeSource = time)
        }.start(wait = false)

    val url: String = runBlocking { "http://127.0.0.1:${engine.engine.resolvedConnectors().first().port}/mcp" }

    suspend fun openAndAbandon(): String {
        val http = HttpClient(ClientCIO) { install(SSE) }
        val transport = StreamableHttpClientTransport(client = http, url = url)
        val client = Client(Implementation(name = "per-call", version = "0"))
        try {
            client.connect(transport)
            client.callTool(name = "query", arguments = emptyMap())
            return transport.sessionId!!
        } finally {
            client.close()
            http.close()
        }
    }

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
