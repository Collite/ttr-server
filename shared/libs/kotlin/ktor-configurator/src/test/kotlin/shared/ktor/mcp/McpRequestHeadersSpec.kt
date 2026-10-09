// SPDX-License-Identifier: Apache-2.0
package shared.ktor.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * [McpRequestHeaders] — the headers a tool handler reads are the headers of the call it serves.
 *
 * The concurrency case is the one that matters, and it runs through the real stack: a CIO server
 * with [installMcpRequestHeaders], an interceptor that hops to `Dispatchers.IO` the way a bearer
 * check does, the MCP SDK's streamable-HTTP transport, and SDK clients each sending their own
 * bearer, many at once. With the headers stashed on a thread-local (the shape this replaced), the
 * handler resumes on whatever worker thread is free and reads nothing or another caller's token —
 * this spec goes red on that shape.
 */
class McpRequestHeadersSpec :
    StringSpec({
        "outside a request both headers are absent, so a gate reading them fails closed" {
            val headers = runBlocking { McpRequestHeaders.current() }
            headers.authorization shouldBe null
            headers.userId shouldBe null
        }

        "the headers survive a dispatcher hop, a suspension and a child coroutine" {
            val seen =
                runBlocking {
                    withContext(McpRequestHeaders("Bearer a", "user-a")) {
                        withContext(Dispatchers.IO) { delay(5) }
                        withTimeout(5.seconds) {
                            withContext(Dispatchers.Default) { McpRequestHeaders.current() }
                        }
                    }
                }
            seen.authorization shouldBe "Bearer a"
            seen.userId shouldBe "user-a"
        }

        "toString never prints a credential" {
            McpRequestHeaders("Bearer secret-token", "user-a").toString() shouldNotContain "secret-token"
        }

        "concurrent MCP tool calls each see their own caller's headers" {
            val server =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    installMcpRequestHeaders()
                    // What a bearer check does in a real door: suspend off the event loop before the
                    // MCP route runs. Installed AFTER the headers, as the doors do.
                    intercept(ApplicationCallPipeline.Plugins) {
                        withContext(Dispatchers.IO) { delay(Random.nextLong(0, 3)) }
                    }
                    mcpStreamableHttp {
                        Server(
                            serverInfo = Implementation(name = "whoami", version = "0"),
                            options =
                                ServerOptions(
                                    capabilities =
                                        ServerCapabilities(
                                            tools = ServerCapabilities.Tools(listChanged = false),
                                        ),
                                ),
                        ).apply {
                            addTool(name = "whoami", description = "echoes the caller's bearer") {
                                delay(Random.nextLong(0, 3))
                                val headers = McpRequestHeaders.current()
                                CallToolResult(
                                    content = listOf(TextContent(text = "${headers.authorization}|${headers.userId}")),
                                )
                            }
                        }
                    }
                }.start(wait = false)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val callers = 24
                val callsEach = 6
                val mismatches =
                    coroutineScope {
                        (1..callers)
                            .map { n ->
                                async(Dispatchers.IO) {
                                    val bearer = "Bearer caller-$n"
                                    val userId = "user-$n"
                                    val http =
                                        HttpClient(ClientCIO) {
                                            install(SSE)
                                            install(
                                                createClientPlugin("Caller$n") {
                                                    onRequest { request, _ ->
                                                        request.headers.append("Authorization", bearer)
                                                        request.headers.append("X-User-Id", userId)
                                                    }
                                                },
                                            )
                                        }
                                    val client = Client(Implementation(name = "caller-$n", version = "0"))
                                    try {
                                        client.connect(
                                            StreamableHttpClientTransport(
                                                client = http,
                                                url = "http://127.0.0.1:$port/mcp",
                                            ),
                                        )
                                        (1..callsEach).mapNotNull {
                                            val result = client.callTool(name = "whoami", arguments = emptyMap())
                                            val seen = (result?.content?.firstOrNull() as? TextContent)?.text
                                            val expected = "$bearer|$userId"
                                            if (seen ==
                                                expected
                                            ) {
                                                null
                                            } else {
                                                "caller-$n expected '$expected', saw '$seen'"
                                            }
                                        }
                                    } finally {
                                        client.close()
                                        http.close()
                                    }
                                }
                            }.awaitAll()
                            .flatten()
                    }
                mismatches.shouldBeEmpty()
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }
    })
