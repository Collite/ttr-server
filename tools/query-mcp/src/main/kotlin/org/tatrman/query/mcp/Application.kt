// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp

import com.typesafe.config.ConfigFactory
import io.grpc.ConnectivityState
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.McpTelemetry
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.installMcpRequestHeaders
import shared.ktor.mcp.loadMcpServerConfig
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.tatrman.query.mcp.mcp.InstrumentedTool
import org.tatrman.query.mcp.mcp.ToolRegistry
import org.tatrman.query.mcp.mcp.installQueryMcp
import org.tatrman.query.mcp.tools.CompileTool
import org.tatrman.query.mcp.tools.QueryTool
import org.tatrman.query.mcp.upstream.GrpcMetadataClient
import org.tatrman.query.mcp.upstream.GrpcQueryRunnerClient
import org.tatrman.query.mcp.upstream.GrpcTranslatorClient
import org.tatrman.query.mcp.upstream.GrpcValidatorClient
import java.util.concurrent.atomic.AtomicInteger

private val logger = LoggerFactory.getLogger("query-mcp")

/**
 * Boot order: load config → OTEL → gRPC clients → tool registry → Ktor server.
 * Shutdown: gRPC channels close on JVM hook.
 */
fun main(): Unit =
    runBlocking {
        val rawConfig = ConfigFactory.load()
        val cfg = QueryMcpConfig.load(rawConfig)
        val mcpServerConfig = loadMcpServerConfig(rawConfig, "query-mcp", 7307)

        logger.info(
            "Starting query-mcp on port {} → {} (transport={})",
            cfg.serverPort,
            cfg.mcpPath,
            cfg.mcpTransport,
        )

        val telemetry = McpTelemetry("query-mcp", mcpServerConfig.telemetryOtlpProtocol ?: "grpc")

        val queryRunner = GrpcQueryRunnerClient(cfg.upstream.queryRunner, cfg.limits.maxMessageBytes)
        val translator = GrpcTranslatorClient(cfg.upstream.translator, cfg.limits.maxMessageBytes)
        val validator = GrpcValidatorClient(cfg.upstream.validator, cfg.limits.maxMessageBytes)
        val metadata =
            GrpcMetadataClient(
                cfg = cfg.upstream.metadata,
                maxMessageBytes = cfg.limits.maxMessageBytes,
                cacheTtl = java.time.Duration.ofSeconds(cfg.metadataDecorationCacheTtlSeconds),
            )
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutting down upstream gRPC channels…")
                runCatching { queryRunner.close() }.onFailure { logger.warn("queryRunner close: {}", it.message) }
                runCatching { translator.close() }.onFailure { logger.warn("translator close: {}", it.message) }
                runCatching { validator.close() }.onFailure { logger.warn("validator close: {}", it.message) }
                runCatching { metadata.close() }.onFailure { logger.warn("metadata close: {}", it.message) }
            },
        )

        val activeRequests = AtomicInteger(0)
        val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        metrics.gauge("query_mcp_active_requests", activeRequests) { it.get().toDouble() }

        val registry =
            ToolRegistry(
                listOf(
                    InstrumentedTool(
                        QueryTool(cfg, queryRunner, metadata),
                        activeRequests,
                        metrics,
                        telemetry.openTelemetry,
                    ),
                    InstrumentedTool(
                        CompileTool(cfg, translator, validator),
                        activeRequests,
                        metrics,
                        telemetry.openTelemetry,
                    ),
                ),
            )
        // Stage 3.5 T5 — register run_query/compile with capabilities-mcp
        // (warn-and-continue; opt in with CAPABILITIES_MCP_URL).
        registerWithCapabilities(rawConfig)

        val readinessProbe: () -> Boolean = {
            // Metadata is best-effort for the side-channel decorator (Phase 2.2);
            // we do NOT require it to be READY for query-mcp to be ready.
            val states =
                listOf(
                    queryRunner.connectivityState(),
                    translator.connectivityState(),
                    validator.connectivityState(),
                )
            states.all { it == ConnectivityState.READY || it == ConnectivityState.IDLE }
        }

        val mcpKtorConfig =
            McpKtorConfig(
                serviceName = "query-mcp",
                serverPort = cfg.serverPort,
                corsAllowedHosts = mcpServerConfig.corsAllowedHosts,
                callLoggingConfig =
                    McpKtorConfig.CallLoggingConfig(
                        level = Level.INFO,
                        customFormat = { request ->
                            val method = request.httpMethod.value
                            val path = request.path()
                            "-> $method $path"
                        },
                    ),
                shutdownUrlPath = mcpServerConfig.shutdownUrlPath,
                connectionIdleTimeoutSeconds = 120,
                defaultToolTimeoutMs = cfg.toolTimeoutsMs.values.maxOrNull() ?: 60_000L,
                toolTimeouts = cfg.toolTimeoutsMs,
                readinessProbe = readinessProbe,
            )

        val verifier = bearerVerifierOf(cfg.security)
        if (verifier == null) {
            logger.warn("bearer signature verification is OFF — claims, roles included, are decoded, not verified")
        }

        val appConfig =
            serverConfig {
                module {
                    installMcpRequestHeaders()
                    verifier?.let { installBearerVerification(it) }
                    installMcpKtorBase(mcpKtorConfig, telemetry.openTelemetrySdk)
                    routing {
                        get("/status") {
                            val states =
                                buildJsonObject {
                                    put("queryRunner", JsonPrimitive(queryRunner.connectivityState().name))
                                    put("translator", JsonPrimitive(translator.connectivityState().name))
                                    put("validator", JsonPrimitive(validator.connectivityState().name))
                                    put("metadata", JsonPrimitive(metadata.connectivityState().name))
                                }
                            val body =
                                buildJsonObject {
                                    put("service", JsonPrimitive("query-mcp"))
                                    put("version", JsonPrimitive("0.1.0"))
                                    put("port", JsonPrimitive(cfg.serverPort))
                                    put("mcp_path", JsonPrimitive(cfg.mcpPath))
                                    put("transport", JsonPrimitive(cfg.mcpTransport))
                                    put("active_requests", JsonPrimitive(activeRequests.get()))
                                    put("upstream_states", states)
                                    put(
                                        "tools",
                                        buildJsonArray {
                                            for (t in registry.all()) add(JsonPrimitive(t.name))
                                        },
                                    )
                                }
                            call.respondText(
                                body.toString(),
                                contentType = ContentType.Application.Json,
                            )
                        }
                        get("/metrics") {
                            call.respondText(
                                metrics.scrape(),
                                contentType = ContentType.parse("text/plain; version=0.0.4"),
                            )
                        }
                    }
                    installQueryMcp(cfg, registry, verifier)
                }
            }

        embeddedServer(
            factory = CIO,
            rootConfig = appConfig,
            configure = {
                connectionIdleTimeoutSeconds = 120
                connectors.add(
                    EngineConnectorBuilder().apply {
                        this.port = cfg.serverPort
                        this.host = "0.0.0.0"
                    },
                )
            },
        ).start(wait = true)
    }
