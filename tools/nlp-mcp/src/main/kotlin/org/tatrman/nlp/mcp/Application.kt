// SPDX-License-Identifier: Apache-2.0
package org.tatrman.nlp.mcp

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.http.HttpHeaders
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.tatrman.capabilities.client.CapabilitiesClient
import org.tatrman.nlp.mcp.client.NlpClient
import org.tatrman.nlp.mcp.telemetry.NlpMcpTelemetry
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool

val logger = LoggerFactory.getLogger("nlp-mcp")

fun main(): Unit =
    runBlocking {
        logger.info("Starting nlp-mcp")

        val config = ConfigFactory.load()
        val telemetry = NlpMcpTelemetry()
        val serverPort = config.getString("server.port").toInt()

        // The analyze call goes over gRPC (NlpService.Analyze) to the nlp
        // service — gRPC is the service contract (RG-P1.S1). The target is read
        // from `nlp.{host,port}` (HOCON resolves the NLP_GRPC_* env overrides
        // the k8s manifest sets). A blank host → `null` and the tool surfaces a
        // "not wired" error rather than crashing boot — local-without-cluster
        // mode (meta-mcp / fuzzy-mcp pattern).
        val nlpClient: NlpClient? = buildNlpClient(config)
        val tools = Tools(nlpClient, telemetry)

        // Register with capabilities-mcp (warn-and-continue). The endpoint is
        // empty by default — opt in with CAPABILITIES_MCP_URL or HOCON
        // `capabilities-mcp.url`. The single `analyze` manifest ships under
        // `src/main/resources/manifests/tools/analyze.yaml`.
        registerWithCapabilities(config)

        val mcpKtorConfig =
            McpKtorConfig(
                serviceName = "nlp-mcp",
                serverPort = serverPort,
                callLoggingConfig =
                    McpKtorConfig.CallLoggingConfig(
                        level = Level.INFO,
                        customFormat = { request ->
                            val httpMethod = request.httpMethod
                            val path = request.path()
                            val origin = request.headers[HttpHeaders.Origin] ?: "No-Origin"
                            val preflightMethod = request.headers[HttpHeaders.AccessControlRequestMethod] ?: "N/A"
                            "-> $httpMethod $path | Origin: $origin | Preflight-Method: $preflightMethod"
                        },
                    ),
            )

        println("nlp-mcp listening on port $serverPort")

        val appConfig =
            serverConfig {
                module {
                    installMcpKtorBase(mcpKtorConfig, telemetry.openTelemetry)

                    mcpStreamableHttp {
                        Server(
                            serverInfo = Implementation(name = "nlp-mcp", version = "0.1.0"),
                            options =
                                ServerOptions(
                                    capabilities =
                                        ServerCapabilities(
                                            tools =
                                                ServerCapabilities.Tools(
                                                    listChanged = true,
                                                ),
                                        ),
                                ),
                        ).also { server ->
                            server.addTool(
                                name = tools.analyzeTool.name,
                                description = tools.analyzeTool.description ?: "",
                                inputSchema = tools.analyzeTool.inputSchema,
                            ) { request ->
                                safeMcpTool("analyze", 60_000) {
                                    tools.analyzeCallback(it)
                                }(request)
                            }
                        }
                    }
                }
            }

        embeddedServer(
            factory = CIO,
            rootConfig = appConfig,
            configure = {
                connectionIdleTimeoutSeconds = 120
                connectors.add(
                    EngineConnectorBuilder().apply {
                        this.port = serverPort
                        this.host = "0.0.0.0"
                    },
                )
            },
        ).start(wait = true)
    }

/**
 * Builds the gRPC client to the nlp service from the `nlp.*` HOCON block.
 * Returns `null` when the host is blank (local no-backend mode) — the [Tools]
 * then surfaces a "not wired" error on every invocation rather than crashing
 * boot. Mirrors the fuzzy-mcp pattern. The channel connects lazily, so a live
 * client is returned without requiring the backend to be up at boot.
 */
internal fun buildNlpClient(config: Config): NlpClient? {
    val host = config.getString("nlp.host")
    if (host.isBlank()) {
        logger.warn("nlp.host is blank — gRPC client disabled; tools will surface 'not wired'")
        return null
    }
    val port = config.getString("nlp.port").toInt()
    val timeout = config.getInt("nlp.timeout")
    logger.info("nlp service (gRPC): $host:$port (deadline: ${timeout}ms)")
    return NlpClient(host, port, deadlineSeconds = (timeout / 1000).toLong().coerceAtLeast(1))
}

/**
 * Loads the manifests under the `manifests/tools` classpath dir and registers
 * each with capabilities-mcp. The call is warn-and-continue: an unreachable
 * registry returns silently and the client re-attempts with backoff.
 */
private fun registerWithCapabilities(config: Config) {
    val url =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: if (config.hasPath("capabilities-mcp.url")) config.getString("capabilities-mcp.url") else ""
    if (url.isBlank()) {
        logger.info("capabilities-mcp.url not set; skipping capability registration")
        return
    }
    val manifests = ManifestLoader().loadAll()
    logger.info("capabilities-mcp.url set to {}; will register {} manifest(s)", url, manifests.size)
    manifests.forEach { capability ->
        runBlocking { CapabilitiesClient.startupRegister(capability, endpoint = url) }
        val capId = if (capability.hasTool()) capability.tool.capabilityId else "(no tool)"
        logger.info("Registered capability with capabilities-mcp: $capId")
    }
}
