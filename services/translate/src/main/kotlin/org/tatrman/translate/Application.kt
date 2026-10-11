// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.tatrman.meta.v1.GetQueryRequest
import org.tatrman.meta.v1.GetQueryResponse
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.VelesServiceGrpcKt
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import shared.logging.IncomingCallLoggingInterceptor
import shared.logging.OutgoingCallLoggingInterceptor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.translate.grpc.TranslatorServiceImpl
import org.tatrman.translate.model.BootFixtureModel
import org.tatrman.translate.model.MetadataServiceModelHandleProvider
import org.tatrman.translate.model.ModelHandleProvider
import org.tatrman.translate.model.StaticModelHandleProvider
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.translate.Application")

/**
 * Representative warm-up query against the boot fixture (`dbo.customer`). Mirrors the
 * hot paths a real pattern query hits — INNER JOIN (with a colliding key name),
 * MIN/SUM aggregates, CAST(text→float), unary minus, `||` concat + SUBSTRING + LIKE,
 * GROUP BY, ORDER BY DESC + row limit — so priming it warms the parse AND unparse halves.
 */
internal val WARMUP_SQL =
    """
    SELECT MIN(a.id) AS mn,
           SUM(CAST(a.name AS FLOAT)) AS s,
           -SUM(CAST(a.name AS FLOAT)) AS neg
    FROM customer a
    JOIN customer b ON a.id = b.id
    WHERE (a.name LIKE a.name || '%' OR a.name LIKE '%' || a.name || '%')
      AND SUBSTRING(a.name, 1, 1) = 'A'
    GROUP BY a.id
    ORDER BY SUM(CAST(a.name AS FLOAT)) DESC
    OFFSET 0 ROWS FETCH NEXT 30 ROWS ONLY
    """.trimIndent()

/**
 * Prime Calcite once at startup. The ~40-50s first-parse cost is JVM-level
 * (class-load + JIT + Janino codegen + planner/rule static init) and
 * model-independent, so we warm against the boot fixture. Best-effort: any
 * failure is logged and swallowed — the worst case is the old cold-start.
 */
private suspend fun warmUpCalcite() {
    val started = System.currentTimeMillis()
    try {
        withContext(Dispatchers.Default) {
            val translator = Translator(BootFixtureModel.handle())
            when (
                val parsed =
                    translator.parseToRelNode(
                        WARMUP_SQL,
                        Language.SQL,
                        SchemaCode.DB,
                        sourceSchema = SchemaCode.DB,
                    )
            ) {
                is ParseResult.Success ->
                    translator.unparseFromRelNode(parsed.plan, Language.SQL, SqlDialect.MSSQL, optimize = true)
                is ParseResult.Failure ->
                    log.warn("Calcite warm-up parse returned failure (non-fatal): {} — {}", parsed.code, parsed.message)
            }
        }
        log.info(
            "Calcite warm-up complete in {} ms — first real query should be warm",
            System.currentTimeMillis() - started,
        )
    } catch (e: Exception) {
        log.warn(
            "Calcite warm-up failed after {} ms (non-fatal); first real query pays cold-start",
            System.currentTimeMillis() - started,
            e,
        )
    }
}

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "translate", 7275)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "translate", 7275))

    // OTel SDK init: configures OTLP trace/metric/log exporters AND installs the bridge
    // into the Logback OpenTelemetryAppender so all SLF4J logs are forwarded to OTLP → Alloy → Loki.
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "translate",
            protocol = System.getenv("TRANSLATE_OTEL_PROTOCOL") ?: "grpc",
        ),
    )

    val useFixture =
        config.hasPath("translate.use-fixture-model") && config.getBoolean("translate.use-fixture-model")

    // Production: a MetadataServiceModelHandleProvider polling the metadata service's GetSnapshot
    // (ETag-skip; first fetch swaps in the real model, boot fixture used until then). Tests / no
    // Veles reachable: the static boot fixture (translate.use-fixture-model = true).
    var metadataChannel: ManagedChannel? = null
    var getQueryFn: (suspend (GetQueryRequest) -> GetQueryResponse)? = null
    val modelProvider: ModelHandleProvider =
        if (useFixture) {
            log.warn("Translate booting with fixture model (translate.use-fixture-model = true)")
            StaticModelHandleProvider(BootFixtureModel.handle())
        } else {
            val host = config.getString("veles.host")
            val port = config.getInt("veles.port")
            val pollSeconds =
                if (config.hasPath(
                        "veles.poll-interval-seconds",
                    )
                ) {
                    config.getInt("veles.poll-interval-seconds")
                } else {
                    600
                }
            val channel =
                ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .intercept(OutgoingCallLoggingInterceptor())
                    .build()
            metadataChannel = channel
            val snapshotDeadlineSeconds =
                if (config.hasPath("veles.snapshot-deadline-seconds")) {
                    config.getInt("veles.snapshot-deadline-seconds")
                } else {
                    30
                }
            val initialRetrySeconds =
                if (config.hasPath("veles.initial-retry-seconds")) {
                    config.getInt("veles.initial-retry-seconds")
                } else {
                    5
                }
            val stub = VelesServiceGrpcKt.VelesServiceCoroutineStub(channel)
            getQueryFn = { req: GetQueryRequest -> stub.withDeadlineAfter(10, TimeUnit.SECONDS).getQuery(req) }
            log.info(
                "Translate using Veles at {}:{} (poll every {}s, snapshot deadline {}s, initial retry {}s)",
                host,
                port,
                pollSeconds,
                snapshotDeadlineSeconds,
                initialRetrySeconds,
            )
            MetadataServiceModelHandleProvider(
                getSnapshot = { req: GetSnapshotRequest ->
                    stub.withDeadlineAfter(snapshotDeadlineSeconds.toLong(), TimeUnit.SECONDS).getSnapshot(req)
                },
                refreshIntervalMs = pollSeconds * 1_000L,
                initialRetryIntervalMs = initialRetrySeconds * 1_000L,
                endpoint = "$host:$port",
            ).also { it.start(this) }
        }

    val translatorService = TranslatorServiceImpl(modelProvider, getQuery = getQueryFn)

    val grpcPort = config.getInt("grpc.port")
    val maxMessageSize = config.getInt("grpc.max-message-size-bytes")
    val reflectionEnabled =
        config.hasPath("grpc.reflection-enabled") && config.getBoolean("grpc.reflection-enabled")
    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            // Tolerate clients' keepalive (30s pings, incl. idle) instead of GOAWAY too_many_pings.
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(maxMessageSize)
            .intercept(IncomingCallLoggingInterceptor())
            .addService(translatorService)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("Translate gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    // Prime Calcite at startup so the FIRST real query doesn't eat the ~40-50s
    // cold-start (class-load + JIT + Janino codegen + planner/rule static init).
    // That cost is JVM-level and model-independent, so we warm against the
    // always-available boot fixture with a query that exercises the hot paths
    // (join + aggregate + cast + ||/SUBSTRING/LIKE + sort/limit), then unparse
    // it to MSSQL to warm the RelToSql half too. Backgrounded so it never blocks
    // server start or readiness.
    launch { warmUpCalcite() }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") {
            // In fixture mode there is no metadata provider, so the fixture IS the intended model → ready.
            // With the metadata provider we are only ready once the first snapshot has loaded; until then
            // current() returns the boot fixture and real schema objects won't resolve, so report 503.
            val metadataProvider = modelProvider as? MetadataServiceModelHandleProvider
            val ready = metadataProvider == null || metadataProvider.lastSuccessfulRefreshTimestamp() != null
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                buildJsonObject {
                    put("status", JsonPrimitive(if (ready) "UP" else "OUT_OF_SERVICE"))
                    put("model_version", JsonPrimitive(modelProvider.current().currentVersion()))
                    if (!ready) put("reason", JsonPrimitive("metadata not yet loaded; serving boot fixture"))
                },
            )
        }
        get("/status") {
            val handle = modelProvider.current()
            val erEntityCount = handle.entities(org.tatrman.plan.v1.SchemaCode.ER, "entity").size
            val erRelationCount = handle.relations().size
            val savedQueryCount = handle.savedQueries(org.tatrman.plan.v1.SchemaCode.OBJ, "query").size
            val lastRefreshTs = (modelProvider as? MetadataServiceModelHandleProvider)?.lastSuccessfulRefreshTimestamp()
            call.respond(
                buildJsonObject {
                    put("service", JsonPrimitive("translate"))
                    put("model_version", JsonPrimitive(handle.currentVersion()))
                    put("grpc_port", JsonPrimitive(grpcPort))
                    put("er_entity_count", JsonPrimitive(erEntityCount))
                    put("er_relation_count", JsonPrimitive(erRelationCount))
                    put("saved_query_count", JsonPrimitive(savedQueryCount))
                    lastRefreshTs?.let { put("last_successful_refresh_timestamp", JsonPrimitive(it)) }
                },
            )
        }
        // Unauthenticated, cluster-internal (see DF decision: /refresh has no auth). Forces an
        // immediate metadata re-poll instead of waiting for the next ~60s tick, so a fresh model
        // version propagates into the plans the translator emits (which is how query-runner learns
        // to invalidate its compiled-plan cache). Golem's /v2/refresh calls this after refreshing
        // metadata and before clearing query-runner.
        post("/refresh") {
            val provider = modelProvider as? MetadataServiceModelHandleProvider
            if (provider == null) {
                call.respond(
                    buildJsonObject {
                        put("status", JsonPrimitive("ok"))
                        put("model_version", JsonPrimitive(modelProvider.current().currentVersion()))
                        put("detail", JsonPrimitive("fixture mode; nothing to refresh"))
                    },
                )
                return@post
            }
            runCatching { provider.refreshOnce() }.fold(
                onSuccess = {
                    call.respond(
                        buildJsonObject {
                            put("status", JsonPrimitive("ok"))
                            put("model_version", JsonPrimitive(modelProvider.current().currentVersion()))
                            provider.lastSuccessfulRefreshTimestamp()?.let {
                                put("last_successful_refresh_timestamp", JsonPrimitive(it))
                            }
                        },
                    )
                },
                onFailure = { ex ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        buildJsonObject {
                            put("status", JsonPrimitive("failed"))
                            put("error", JsonPrimitive(ex.message ?: ex.toString()))
                        },
                    )
                },
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down translate gRPC server")
        (modelProvider as? MetadataServiceModelHandleProvider)?.stop()
        metadataChannel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        grpcServer.shutdown()
    }
}
