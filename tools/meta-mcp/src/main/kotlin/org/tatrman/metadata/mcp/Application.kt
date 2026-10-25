// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.mcp

import com.typesafe.config.ConfigFactory
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
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
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool
import shared.ktor.mcp.McpTelemetry
import org.tatrman.veles.client.GrpcMetadataGrpcClient
import org.tatrman.veles.client.MetadataGrpcClient
import org.tatrman.capabilities.client.CapabilitiesClient
import org.tatrman.capabilities.v1.Capability

val logger = LoggerFactory.getLogger("veles-mcp")

fun main(args: Array<String>): Unit =
    runBlocking {
        logger.info("Starting veles-mcp")

        val config = ConfigFactory.load()
        val telemetry = McpTelemetry("veles-mcp", "grpc")
        val serverPort = config.getString("server.port").toInt()

        // All metadata reads go over gRPC to the metadata service (the service exposes no REST
        // data endpoints — only health/ready/status/metrics/refresh). Optional: if the host/port
        // isn't configured the tools surface a "not wired" error rather than crashing boot. The
        // target is read from the `metadata{}` block in application.conf (see [buildGrpcClient]).
        val grpcClient: MetadataGrpcClient? = buildGrpcClient(config)
        val tools = Tools(grpcClient)

        // Phase 2.1 T6 — register with capabilities-mcp (warn-and-continue).
        // When the registry is unreachable the call still returns; the
        // background retry loop re-attempts register with exponential backoff.
        // The `capabilities-mcp` endpoint is empty by default — opt in with
        // `CAPABILITIES_MCP_URL=http://capabilities-mcp:7501` (or wherever the
        // capabilities registry is deployed in the target cluster).
        registerWithCapabilities(config)

        val mcpConfig =
            McpKtorConfig(
                serviceName = "veles-mcp",
                serverPort = serverPort,
                shutdownUrlPath = "/shutdown",
                connectionIdleTimeoutSeconds = 120,
            )

        val appConfig =
            serverConfig {
                module {
                    installMcpKtorBase(mcpConfig, telemetry.openTelemetrySdk)

                    install(CallLogging) {
                        level = Level.INFO
                        format { call ->
                            val status = call.response.status()?.value ?: "Unhandled"
                            val method = call.request.httpMethod.value
                            val path = call.request.path()
                            val origin = call.request.headers[HttpHeaders.Origin] ?: "No-Origin"
                            "-> [$status] $method $path | Origin: $origin"
                        }
                    }

                    mcpStreamableHttp {
                        val server =
                            Server(
                                serverInfo = Implementation(name = "veles-mcp", version = "0.1.0"),
                                options =
                                    ServerOptions(
                                        capabilities =
                                            ServerCapabilities(
                                                tools = ServerCapabilities.Tools(listChanged = false),
                                            ),
                                    ),
                            )

                        server.addTool(
                            name = tools.getTablesTool.name,
                            description = tools.getTablesTool.description ?: "",
                            inputSchema = tools.getTablesTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getTablesTool.name, 60_000) {
                                tools.getTablesCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getTableDetailsTool.name,
                            description = tools.getTableDetailsTool.description ?: "",
                            inputSchema = tools.getTableDetailsTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getTableDetailsTool.name, 60_000) {
                                tools.getTableDetailsCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getEntitiesTool.name,
                            description = tools.getEntitiesTool.description ?: "",
                            inputSchema = tools.getEntitiesTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getEntitiesTool.name, 60_000) {
                                tools.getEntitiesCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getEntityDetailsTool.name,
                            description = tools.getEntityDetailsTool.description ?: "",
                            inputSchema = tools.getEntityDetailsTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getEntityDetailsTool.name, 60_000) {
                                tools.getEntityDetailsCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getRelationshipsTool.name,
                            description = tools.getRelationshipsTool.description ?: "",
                            inputSchema = tools.getRelationshipsTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getRelationshipsTool.name, 60_000) {
                                tools.getRelationshipsCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getPatternQueriesTool.name,
                            description = tools.getPatternQueriesTool.description ?: "",
                            inputSchema = tools.getPatternQueriesTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getPatternQueriesTool.name, 60_000) {
                                tools.getPatternQueriesCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getPatternQueryDetailsTool.name,
                            description = tools.getPatternQueryDetailsTool.description ?: "",
                            inputSchema = tools.getPatternQueryDetailsTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getPatternQueryDetailsTool.name, 60_000) {
                                tools.getPatternQueryDetailsCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getSqlQueriesTool.name,
                            description = tools.getSqlQueriesTool.description ?: "",
                            inputSchema = tools.getSqlQueriesTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getSqlQueriesTool.name, 60_000) {
                                tools.getSqlQueriesCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getSqlQueryDetailsTool.name,
                            description = tools.getSqlQueryDetailsTool.description ?: "",
                            inputSchema = tools.getSqlQueryDetailsTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getSqlQueryDetailsTool.name, 60_000) {
                                tools.getSqlQueryDetailsCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getStoredProceduresTool.name,
                            description = tools.getStoredProceduresTool.description ?: "",
                            inputSchema = tools.getStoredProceduresTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getStoredProceduresTool.name, 60_000) {
                                tools.getStoredProceduresCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getStoredProcedureDetailsTool.name,
                            description = tools.getStoredProcedureDetailsTool.description ?: "",
                            inputSchema = tools.getStoredProcedureDetailsTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getStoredProcedureDetailsTool.name, 60_000) {
                                tools.getStoredProcedureDetailsCallback(it)
                            }(request)
                        }

                        // C1 — get_model tool
                        server.addTool(
                            name = tools.getModelTool.name,
                            description = tools.getModelTool.description ?: "",
                            inputSchema = tools.getModelTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getModelTool.name, 30_000) {
                                tools.getModelCallback(it)
                            }(request)
                        }

                        // Phase 07 A4 / DF-ME01 — cnc.role tools routed through the gRPC client.
                        server.addTool(
                            name = tools.listRolesTool.name,
                            description = tools.listRolesTool.description ?: "",
                            inputSchema = tools.listRolesTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.listRolesTool.name, 60_000) {
                                tools.listRolesCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.getRolesForEntityTool.name,
                            description = tools.getRolesForEntityTool.description ?: "",
                            inputSchema = tools.getRolesForEntityTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.getRolesForEntityTool.name, 60_000) {
                                tools.getRolesForEntityCallback(it)
                            }(request)
                        }

                        // Golem P4 S4.2 — resolve_area tool (zero-logic wrapper over veles's
                        // ResolveArea RPC; resolves a Shem's `areas: [...]` to package sets).
                        server.addTool(
                            name = tools.resolveAreaTool.name,
                            description = tools.resolveAreaTool.description ?: "",
                            inputSchema = tools.resolveAreaTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.resolveAreaTool.name, 30_000) {
                                tools.resolveAreaCallback(it)
                            }(request)
                        }

                        server
                    }
                }
            }

        println("veles-mcp server running and listening on port $serverPort")

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

// =============================================================================
// Phase 2.1 (fork) — gRPC client wiring
// =============================================================================

/**
 * Review-004 R2 — resolve the veles gRPC client target from the
 * `metadata{}` block in `application.conf`. `metadata.host` / `metadata.port`
 * default to `"meta"` / `7261` and are overridden by the `VELES_GRPC_HOST`
 * / `VELES_GRPC_PORT` env vars (HOCON `${?…}` substitution) — the exact env
 * vars the k8s deployment sets.
 *
 * A blank host returns `null` so the tools report "not wired" rather than the
 * process crashing — the local no-backend mode (`VELES_GRPC_HOST=""`).
 *
 * The previous bug read `METADATA_GRPC_HOST` / `METADATA_GRPC_PORT` (which
 * nothing sets) with a stale `7204` default, so the in-cluster pod never
 * connected and every tool returned not-wired. `main()` and the wiring test
 * both go through this one function so they cannot diverge again.
 */
internal fun buildGrpcClient(config: com.typesafe.config.Config): MetadataGrpcClient? {
    val host = if (config.hasPath("metadata.host")) config.getString("metadata.host") else ""
    val port = if (config.hasPath("metadata.port")) config.getString("metadata.port").toInt() else 7261
    return if (host.isNotBlank()) {
        logger.info("Metadata gRPC client at {}:{}", host, port)
        GrpcMetadataGrpcClient(host = host, port = port)
    } else {
        logger.warn("metadata.host is blank — all metadata tools will report not-wired errors.")
        null
    }
}

// =============================================================================
// Phase 2.1 T6 — capabilities-mcp registration
// =============================================================================

/**
 * Review-004 R5.1 — load the authored `ToolCapability` manifests from
 * `src/main/resources/manifests/tools/` (the per-tool YAML dir) and build one [Capability]
 * per manifest. The previous code shipped a single [Capability] shim
 * that impersonated `meta.get_model:v1` and folded the other tools
 * into `search_tags` — the registry saw 1 capability instead of one per
 * manifest, and each tool was undiscoverable by its own id.
 *
 * Each [Capability] is registered with capabilities-mcp separately
 * (one HTTP call per tool); each call returns its own
 * `CapabilitiesClientHandle` and the background heartbeat loop runs
 * per-handle. Failures on any one tool don't block the others.
 */
private fun velesMcpCapabilities(): List<Capability> {
    val loader = ManifestLoader()
    return loader.loadAll()
}

/**
 * Phase 2.1 T6 — register with capabilities-mcp at startup (warn-and-continue).
 * When the registry is unreachable the call still returns; the background
 * retry loop re-attempts register with exponential backoff. Set
 * `CAPABILITIES_MCP_URL=http://capabilities-mcp:7501` to enable.
 *
 * Review-004 R5.1 — registers one capability per authored manifest
 * (6 today: list_objects, get_object, search, list_queries, get_model,
 * resolve_area). Each registration is independent; one failure does not
 * block the others.
 */
internal fun registerWithCapabilities(config: com.typesafe.config.Config) {
    val endpoint =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: config.getString("capabilities-mcp.url")
    val capabilities = velesMcpCapabilities()
    if (capabilities.isEmpty()) {
        logger.info(
            "No veles-mcp capabilities to register (manifests dir empty or missing). " +
                "If you expected capabilities here, check the build's classpath for the manifests/ tree.",
        )
        return
    }
    if (endpoint.isBlank()) {
        logger.info(
            "CAPABILITIES_MCP_URL not set — {} veles-mcp capabilities are not registered " +
                "with capabilities-mcp. The registry search will not find them until registration is configured.",
            capabilities.size,
        )
        return
    }
    var registered = 0
    for (cap in capabilities) {
        val id = cap.tool.capabilityId
        val handle =
            CapabilitiesClient.startupRegister(
                capability = cap,
                endpoint = endpoint,
                heartbeatIntervalMs = 30_000,
            )
        if (handle.registrationId != null) {
            registered++
            logger.info("veles-mcp registered '{}' with capabilities-mcp at {}", id, endpoint)
        } else {
            logger.warn(
                "veles-mcp startup register for '{}' at {} not yet complete (registry may be " +
                    "unreachable); background retry will continue.",
                id,
                endpoint,
            )
        }
    }
    logger.info("veles-mcp: {}/{} capabilities registered", registered, capabilities.size)
}
