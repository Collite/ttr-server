// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp

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
import org.slf4j.event.Level
import org.tatrman.grounding.mcp.client.GroundingGrpcClient
import org.tatrman.grounding.mcp.telemetry.GroundingMcpTelemetry
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool

private const val TOOL_TIMEOUT_MS = 25_000L

fun main(): Unit =
    runBlocking {
        val config = ConfigFactory.load()
        val telemetry = GroundingMcpTelemetry()
        val serverPort = config.getString("server.port").toInt()
        val deadline = config.getLong("grounding.deadline-seconds")

        val mcpKtorConfig =
            McpKtorConfig(
                serviceName = "grounding-mcp",
                serverPort = serverPort,
                callLoggingConfig =
                    McpKtorConfig.CallLoggingConfig(
                        level = Level.INFO,
                        customFormat = { request ->
                            val origin = request.headers[HttpHeaders.Origin] ?: "No-Origin"
                            "-> ${request.httpMethod} ${request.path()} | Origin: $origin"
                        },
                    ),
            )

        val chrono = grpcClient(config, "chrono", deadline)
        val geo = grpcClient(config, "geo", deadline)
        val money = grpcClient(config, "money", deadline)

        // RG-P3.S2.T6 — register the kind-named capability ids (grounding.time|geo|money:v1) with
        // capabilities-mcp (warn-and-continue; a no-op unless CAPABILITIES_MCP_URL is set).
        registerWithCapabilities(config)

        try {
            val tools = Tools(chrono, geo, money)
            println("grounding-mcp running on port $serverPort (chrono/geo/money)")

            val appConfig =
                serverConfig {
                    module {
                        installMcpKtorBase(mcpKtorConfig, telemetry.openTelemetry)
                        mcpStreamableHttp {
                            Server(
                                serverInfo = Implementation(name = "grounding-mcp-server", version = "0.1.0"),
                                options =
                                    ServerOptions(
                                        capabilities =
                                            ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
                                    ),
                            ).also { server ->
                                server.addTool(tools.groundTimeTool) { req ->
                                    safeMcpTool("ground_time", TOOL_TIMEOUT_MS) { tools.groundTimeCallback(it) }(req)
                                }
                                server.addTool(tools.groundGeoTool) { req ->
                                    safeMcpTool("ground_geo", TOOL_TIMEOUT_MS) { tools.groundGeoCallback(it) }(req)
                                }
                                server.addTool(tools.groundMoneyTool) { req ->
                                    safeMcpTool("ground_money", TOOL_TIMEOUT_MS) { tools.groundMoneyCallback(it) }(req)
                                }
                                server.addTool(tools.scanTimeTool) { req ->
                                    safeMcpTool("scan_time", TOOL_TIMEOUT_MS) { tools.scanTimeCallback(it) }(req)
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
        } finally {
            chrono.close()
            geo.close()
            money.close()
        }
    }

private fun grpcClient(
    config: com.typesafe.config.Config,
    service: String,
    deadline: Long,
): GroundingGrpcClient =
    GroundingGrpcClient(
        serviceName = service,
        host = config.getString("grounding.$service.host"),
        port = config.getString("grounding.$service.port").toInt(),
        deadlineSeconds = deadline,
    )
