// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.GrpcDispatcherClient
import org.tatrman.query.client.GrpcSavedQueryClient
import org.tatrman.query.client.GrpcTranslatorClient
import org.tatrman.query.client.GrpcValidatorClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.mcp.identity.BearerVerifier
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.query.flight.FlightAuthenticator
import org.tatrman.query.flight.FlightConfig
import org.tatrman.query.flight.FlightEdge
import org.tatrman.query.flight.GrpcVelesCatalogClient
import org.tatrman.query.flight.IdentityWorldMiddleware
import org.tatrman.query.flight.KeycloakPasswordGrant
import org.tatrman.query.flight.LoopbackStatementRunner
import org.tatrman.query.flight.PostgresReceiptStore
import org.tatrman.query.flight.ReceiptSink
import org.tatrman.query.flight.ReceiptStore
import org.tatrman.query.flight.StatementListener
import org.tatrman.query.flight.receiptRoutes
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.world.Worlds
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.logging.IncomingCallLoggingInterceptor
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.time.Duration

private val log = LoggerFactory.getLogger("org.tatrman.query.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "query", 7305)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "query", 7305))

    // OTel SDK init: configures OTLP trace/metric/log exporters AND installs the bridge
    // into the Logback OpenTelemetryAppender so all SLF4J logs are forwarded to OTLP → Alloy → Loki.
    // The instance is also handed to QueryServiceImpl so its orchestration spans
    // (query.run → parse/validate/dispatch) export on the same SDK (Stage 4.1 T3).
    val openTelemetry =
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "query",
                protocol = System.getenv("QUERY_OTEL_PROTOCOL") ?: "grpc",
            ),
        )

    val useFixture = config.hasPath("query.use-fixture") && config.getBoolean("query.use-fixture")

    val cache =
        CompiledPlanCache(
            maxEntries = config.getLong("cache.max-entries"),
            expireAfterWrite = Duration.ofMinutes(config.getLong("cache.expire-after-write-minutes")),
        )

    val retry =
        RetryPolicy(
            maxAttempts = config.getInt("retry.max-attempts"),
            initialBackoffMillis = config.getLong("retry.initial-backoff-millis"),
            multiplier = config.getDouble("retry.multiplier"),
            jitterPercent = config.getInt("retry.jitter-percent"),
        )

    val translatorClient = pickTranslatorClient(config, useFixture)
    val translatorDetectClient = translatorClient as TranslatorDetectClient
    val translatorTranslateClient = translatorClient as TranslatorTranslateClient
    val validatorClient = pickValidatorClient(config, useFixture)
    val dispatcherClient = pickDispatcherClient(config, useFixture)
    // LQ (L-31): RunSavedQuery reads a saved query's stored plan from veles; no fixture stands in for it.
    val savedQueryClient =
        if (useFixture) {
            null
        } else {
            GrpcSavedQueryClient(
                host = config.getString("veles.host"),
                port = config.getInt("veles.port"),
                deadlineSeconds = config.getLong("veles.deadline-seconds"),
            )
        }

    // BI (C-9) — the worlds this door serves, parsed once; a malformed table fails the boot, by name.
    val worlds = Worlds.fromConfig(config)
    log.info(
        "Worlds: {}",
        if (worlds.connections.isEmpty()) "none (one world, the default connection)" else worlds.toString(),
    )

    val service =
        QueryServiceImpl(
            rawTranslator = translatorClient,
            rawTranslatorDetect = translatorDetectClient,
            rawTranslatorTranslate = translatorTranslateClient,
            rawValidator = validatorClient,
            rawDispatcher = dispatcherClient,
            cache = cache,
            retry = retry,
            openTelemetry = openTelemetry,
            rawSavedQueries = savedQueryClient,
            worlds = worlds,
        )

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
            // Log every incoming RPC with request + response CONTENT (other services
            // already do this; query-runner (pre-fork) was missing it, so its in-band errors
            // — e.g. the translator_rejected ResultBatch — were invisible).
            .intercept(IncomingCallLoggingInterceptor())
            .addService(service)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    // BI (contracts C-1…C-6) — the Flight SQL edge, a client of this door over loopback. Never in fixture mode: it
    // reads its catalog from Veles, which the fixture clients do not stand in for. Every caller is verified (C-2) —
    // an enabled edge without an issuer to verify against does not start — and every statement leaves a receipt
    // when the receipts database is configured (C-6).
    val flightConfig = FlightConfig.fromConfig(config)
    if (flightConfig.enabled && useFixture) log.warn("Flight edge enabled but query.use-fixture = true — not started.")
    val flight =
        if (flightConfig.enabled && !useFixture) {
            val auth = flightConfig.auth
            require(auth.issuer.isNotBlank()) {
                "flight.enabled = true needs flight.auth.issuer (QUERY_FLIGHT_AUTH_ISSUER): the edge verifies every caller"
            }
            val verifier =
                JwksBearerVerifier(
                    HttpJwksKeySource(auth.effectiveJwksUri),
                    issuer = auth.issuer,
                    audience = auth.audience.ifBlank { null },
                )
            val passwords =
                auth.passwordClient.takeIf { it.isNotBlank() }?.let {
                    KeycloakPasswordGrant(auth.effectiveTokenUrl, it, auth.passwordClientSecret)
                }
            log.info(
                "Flight edge: bearers verified against {} (jwks {}); password login {}",
                auth.issuer,
                auth.effectiveJwksUri,
                passwords?.let { "on client ${auth.passwordClient} at ${auth.effectiveTokenUrl}" } ?: "off",
            )
            if (auth.audience.isBlank()) {
                log.warn(
                    "Flight edge: no audience set — any client's token from the realm is accepted",
                )
            }
            val receiptStore = PostgresReceiptStore.fromConfig(config)
            val receiptSink = receiptStore?.let { ReceiptSink(it) }
            when (val off = PostgresReceiptStore.offReason(config)) {
                null -> log.info("Flight edge: receipts on (bi_receipts)")
                PostgresReceiptStore.OFF_NO_HOST -> log.info("Flight edge: receipts off ({})", off)
                else -> log.warn("Flight edge: receipts off ({}) — statements run, none is receipted", off)
            }
            val runner =
                LoopbackStatementRunner(
                    flightConfig.loopbackHost,
                    flightConfig.loopbackPort,
                    flightConfig.maxMessageSizeBytes,
                )
            val veles =
                GrpcVelesCatalogClient(
                    host = config.getString("veles.host"),
                    port = config.getInt("veles.port"),
                    deadlineSeconds = config.getLong("veles.deadline-seconds"),
                )
            val edge =
                FlightEdge.create(
                    config = flightConfig,
                    runner = runner,
                    veles = veles,
                    identity = IdentityWorldMiddleware.Factory(FlightAuthenticator(verifier, passwords), worlds),
                    worlds = worlds,
                    outcomes = receiptSink ?: StatementListener { },
                    openTelemetry = openTelemetry,
                    // In order: the sink drains into the store before the store's pool closes.
                    closeables = listOfNotNull(runner, veles, receiptSink, receiptStore),
                )
            FlightWiring(edge, verifier, receiptStore)
        } else {
            null
        }
    val flightEdge = flight?.edge
    // On the application's own thread, before the gRPC server: an enabled edge that cannot listen fails the start
    // (and the process), as an enabled edge without an issuer does. Its statements reach the door once gRPC is up.
    try {
        flightEdge?.start()
    } catch (e: IllegalStateException) {
        runCatching { flightEdge?.close() }
        throw e
    }

    launch {
        grpcServer.start()
        log.info("Query gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") {
            val ready = FlightEdge.ready(doorReady = useFixture || (translatorClient is AutoCloseable), flightEdge)
            if (ready) {
                call.respond(buildJsonObject { put("status", "UP") })
            } else {
                call.respond(
                    io.ktor.http.HttpStatusCode.ServiceUnavailable,
                    buildJsonObject { put("status", "NOT_READY") },
                )
            }
        }
        get("/status") {
            val stats = cache.stats()
            call.respond(
                buildJsonObject {
                    put("service", "query")
                    put("grpc_port", grpcPort)
                    put("active_runs", service.activeRunCount)
                    put(
                        "flight",
                        flight?.let {
                            JsonObject(
                                it.edge.status() + ("receipts" to JsonPrimitive(it.receipts != null)),
                            )
                        }
                            ?: FlightEdge.DISABLED_STATUS,
                    )
                    put("worlds", JsonArray(worlds.names.map { JsonPrimitive(it) }))
                    putJsonObject("cache") {
                        put("entries", stats.entries)
                        put("max_entries", stats.maxEntries)
                        put("hits", stats.hits)
                        put("misses", stats.misses)
                        put("invalidations", stats.invalidations)
                        put("evictions", stats.evictions)
                        put("current_model_version", stats.currentModelVersion)
                    }
                },
            )
        }
        // C-6 — the Flight edge's receipts, query-platform-admin only (verified like the edge's callers).
        flight?.receipts?.let { store -> receiptRoutes(store, flight.verifier) }
        // Unauthenticated, cluster-internal (see DF decision: /refresh has no auth). Drops every
        // cached compiled plan so the next query recompiles against the latest model. Golem's
        // /v2/refresh calls this AFTER forcing the translator to re-poll metadata, so the recompiled
        // plans carry the new model version.
        post("/refresh") {
            val cleared = cache.clear()
            call.respond(
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("cleared_entries", JsonPrimitive(cleared))
                },
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down Query")
        flightEdge?.close()
        grpcServer.shutdown()
        if (translatorClient is AutoCloseable) runCatching { translatorClient.close() }
        if (validatorClient is AutoCloseable) runCatching { validatorClient.close() }
        if (dispatcherClient is AutoCloseable) runCatching { dispatcherClient.close() }
    }
}

/** The Flight edge and what the HTTP side reads from it: the bearer verifier and the receipt store (null = off). */
private class FlightWiring(
    val edge: FlightEdge,
    val verifier: BearerVerifier,
    val receipts: ReceiptStore?,
)

private fun pickTranslatorClient(
    config: Config,
    useFixture: Boolean,
): TranslatorClient {
    if (useFixture) {
        log.warn("Query booting with fixture clients (query.use-fixture = true).")
        return object : TranslatorClient, TranslatorDetectClient, TranslatorTranslateClient {
            override suspend fun parse(request: org.tatrman.translate.v1.ParseRequest) =
                org.tatrman.translate.v1
                    .ParseResponse
                    .newBuilder()
                    .setContext(request.context)
                    .build()

            override suspend fun translate(request: org.tatrman.translate.v1.TranslateRequest) =
                org.tatrman.translate.v1
                    .TranslateResponse
                    .newBuilder()
                    .setContext(request.context)
                    .build()

            override suspend fun detect(request: org.tatrman.translate.v1.DetectSchemaRequest) =
                org.tatrman.translate.v1
                    .DetectSchemaResponse
                    .newBuilder()
                    .setDecision(org.tatrman.translate.v1.SchemaDecision.SCHEMA_DECISION_UNSPECIFIED)
                    .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                    .build()
        }
    }
    return GrpcTranslatorClient(
        host = config.getString("translate.host"),
        port = config.getInt("translate.port"),
        deadlineSeconds = config.getLong("translate.deadline-seconds"),
    )
}

private fun pickValidatorClient(
    config: Config,
    useFixture: Boolean,
): ValidatorClient {
    if (useFixture) {
        return ValidatorClient { req ->
            org.tatrman.validate.v1
                .ValidateResponse
                .newBuilder()
                .setPlan(req.plan)
                .setContext(req.context)
                .build()
        }
    }
    return GrpcValidatorClient(
        host = config.getString("validate.host"),
        port = config.getInt("validate.port"),
        deadlineSeconds = config.getLong("validate.deadline-seconds"),
    )
}

private fun pickDispatcherClient(
    config: Config,
    useFixture: Boolean,
): DispatcherClient {
    if (useFixture) {
        return DispatcherClient { _ ->
            kotlinx.coroutines.flow.flowOf(
                org.tatrman.worker.v1
                    .ResultBatch
                    .newBuilder()
                    .setIsFirst(true)
                    .setIsLast(true)
                    .setArrowIpc(com.google.protobuf.ByteString.EMPTY)
                    .build(),
            )
        }
    }
    return GrpcDispatcherClient(
        host = config.getString("dispatch.host"),
        port = config.getInt("dispatch.port"),
    )
}
