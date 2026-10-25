// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.mcp

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
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.tatrman.capabilities.client.CapabilitiesClient
import org.tatrman.fuzzy.mcp.client.FuzzyClient
import org.tatrman.fuzzy.mcp.client.FuzzyGrpcClient
import org.tatrman.fuzzy.mcp.client.FuzzyRestClient
import org.tatrman.fuzzy.mcp.telemetry.FuzzyMcpTelemetry
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool

val logger = LoggerFactory.getLogger("fuzzy-mcp")

fun main(): Unit =
    runBlocking {
        logger.info("Starting fuzzy-mcp")

        val config = ConfigFactory.load()
        val telemetry = FuzzyMcpTelemetry()
        val serverPort = config.getString("server.port").toInt()

        // All match calls go over gRPC to the fuzzy service (REST is opt-in
        // via `fuzzy.client.protocol=REST`). The target is read from
        // `fuzzy.client.{host,port}` (HOCON resolves the FUZZY_GRPC_* env
        // overrides the k8s manifest sets). A blank host → `null` and the
        // tools surface a "not wired" error rather than crashing boot —
        // local-without-cluster mode.
        val fuzzyClient: FuzzyClient? = buildFuzzyClient(config, telemetry)
        val tools = Tools(fuzzyClient, telemetry)

        // Stage 2.2 T6 — register with capabilities-mcp (warn-and-continue).
        // The `capabilities-mcp` endpoint is empty by default — opt in with
        // `CAPABILITIES_MCP_URL=http://capabilities-mcp:7103` or set
        // `capabilities-mcp.url` in HOCON. The single `match` manifest ships
        // under `src/main/resources/manifests/tools/match.yaml`.
        registerWithCapabilities(config)

        val mcpKtorConfig =
            McpKtorConfig(
                serviceName = "fuzzy-mcp",
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

        println("fuzzy-mcp listening on port $serverPort")

        val appConfig =
            serverConfig {
                module {
                    installMcpKtorBase(mcpKtorConfig, telemetry.openTelemetry)

                    mcpStreamableHttp {
                        Server(
                            serverInfo = Implementation(name = "fuzzy-mcp", version = "0.1.0"),
                            options =
                                io.modelcontextprotocol.kotlin.sdk.server.ServerOptions(
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
                                name = tools.matchTool.name,
                                description = tools.matchTool.description ?: "",
                                inputSchema = tools.matchTool.inputSchema,
                            ) { request ->
                                safeMcpTool("match", 15_000) {
                                    tools.matchCallback(it)
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
 * Builds the gRPC client (default) or REST client (opt-in) from the
 * `fuzzy.client.*` HOCON block. Returns `null` when the gRPC host is blank
 * (local no-backend mode) — the [Tools] then surfaces a "not wired" error
 * on every invocation rather than crashing boot. Mirrors the meta-mcp
 * pattern (Stage 2.1 R2 re-review, 2026-06-13).
 */
internal fun buildFuzzyClient(
    config: Config,
    telemetry: FuzzyMcpTelemetry,
): FuzzyClient? {
    val protocol = config.getString("fuzzy.client.protocol").lowercase()
    return if (protocol == "grpc") {
        val host = config.getString("fuzzy.client.host")
        if (host.isBlank()) {
            logger.warn("fuzzy.client.host is blank — gRPC client disabled; tools will surface 'not wired'")
            null
        } else {
            val port = config.getString("fuzzy.client.grpc.port").toInt()
            FuzzyGrpcClient(host, port, telemetry)
        }
    } else {
        val host = config.getString("fuzzy.client.host")
        if (host.isBlank()) {
            logger.warn("fuzzy.client.host is blank — REST client disabled; tools will surface 'not wired'")
            null
        } else {
            val url = "http://$host:${config.getString("fuzzy.client.rest.port")}"
            FuzzyRestClient(url, telemetry)
        }
    }
}

/**
 * Loads the manifests under the `manifests/tools` classpath dir and
 * registers each with capabilities-mcp. The call is warn-and-continue:
 * a registry that's unreachable returns silently and a background coroutine
 * re-attempts with exponential backoff (1s → 60s cap).
 */
private fun registerWithCapabilities(config: Config) {
    val url =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: if (config.hasPath("capabilities-mcp.url")) config.getString("capabilities-mcp.url") else ""
    if (url.isBlank()) {
        logger.info("capabilities-mcp.url not set; skipping capability registration")
        return
    }
    logger.info("capabilities-mcp.url set to {}; will register {} manifest(s)", url, ManifestLoader().loadAll().size)
    val manifests = ManifestLoader().loadAll()
    manifests.forEach { capability ->
        runBlocking { CapabilitiesClient.startupRegister(capability, endpoint = url) }
        val capId = if (capability.hasTool()) capability.tool.capabilityId else "(no tool)"
        logger.info("Registered capability with capabilities-mcp: $capId")
    }
}
