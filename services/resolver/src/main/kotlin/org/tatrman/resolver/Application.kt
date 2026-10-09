// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.resolver.client.GrpcGroundingClient
import org.tatrman.resolver.client.GrpcFuzzyClient
import org.tatrman.resolver.client.GrpcNlpClient
import org.tatrman.resolver.grpc.ResolverGrpcService
import org.tatrman.resolver.mcp.ResolveDoor
import org.tatrman.resolver.mcp.ResolveDoorHandler
import org.tatrman.resolver.mcp.installResolveDoor
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.LiveMetadataRegistryAdapter
import org.tatrman.resolver.registry.LexiconArchiveRegistrySource
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.token.ResumeTokenCodec
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.installMcpRequestHeaders
import org.tatrman.resolver.telemetry.resolverTelemetry
import shared.logging.IncomingCallLoggingInterceptor
import shared.logging.OtelContextServerInterceptor
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.resolver.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "resolver", 7275)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "resolver", 7275))

    // OTel SDK init: OTLP trace/metric/log exporters AND the bridge into the Logback
    // OpenTelemetryAppender, so SLF4J records ship over OTLP → Alloy → Loki. TG-P0-F1 — until
    // 2026-08-14 this call did not exist here, alone among the services, and everything below
    // silently ran on `OpenTelemetry.noop()`. See `telemetry/ResolverTelemetry.kt` for what that
    // cost and for what it still does NOT buy (trace correlation needs kantheon#34).
    val otel = resolverTelemetry(config)

    val grpcPort = config.getInt("grpc.port")
    val maxMessageSize = config.getInt("grpc.max-message-size-bytes")
    val reflectionEnabled =
        config.hasPath("grpc.reflection-enabled") && config.getBoolean("grpc.reflection-enabled")

    // Upstream clients — nlp (parse + capability matrix), lex-matcher (vocabulary match) and,
    // when the estate opts in, a grounding kernel (R1). NONE is an LLM client of this service
    // (RS-23); see ResolverPipeline's KDoc for why the kernel needs that sentence qualified.
    val nlpClient =
        GrpcNlpClient(config.getString("nlp.host"), config.getInt("nlp.port"), config.getLong("nlp.deadline-seconds"))
    val fuzzyClient =
        GrpcFuzzyClient(
            config.getString("fuzzy.host"),
            config.getInt("fuzzy.port"),
            config.getLong("fuzzy.deadline-seconds"),
        )

    // ✅ R1 (Bora, 2026-08-28) — the grounding rung, off unless the estate says otherwise.
    // Built here rather than lazily so an unreachable kernel is a BOOT-time log line and not a
    // surprise on the first dated question; the channel itself connects lazily, so this does not
    // make the kernel a startup dependency.
    val groundingEnabled = config.getBoolean("resolver.grounding.enabled")
    val resolverPackage = config.getString("resolver.package").trim()
    val groundingClient =
        if (!groundingEnabled) {
            null
        } else {
            if (resolverPackage.isEmpty()) {
                // Not fatal: `ResolveContext.package` may still supply it per request, and a
                // multi-estate deployment would legitimately leave this blank. Said out loud
                // because the single-estate case is the common one and its failure is silent —
                // the rung simply never runs and dated questions keep getting refused.
                log.warn(
                    "resolver.grounding.enabled=true but resolver.package is unset — grounding will " +
                        "only run for callers that set ResolveContext.package. Set RESOLVER_PACKAGE " +
                        "to the estate's metadata package for a single-estate deployment.",
                )
            }
            GrpcGroundingClient(
                config.getString("resolver.grounding.chrono-host"),
                config.getInt("resolver.grounding.chrono-port"),
                config.getLong("resolver.grounding.deadline-seconds"),
            ).also {
                log.info(
                    "grounding rung ENABLED — chrono at {}:{}, package={}",
                    config.getString("resolver.grounding.chrono-host"),
                    config.getInt("resolver.grounding.chrono-port"),
                    resolverPackage.ifBlank { "<per-request>" },
                )
            }
        }
    if (!groundingEnabled) {
        log.info(
            "grounding rung disabled (resolver.grounding.enabled=false) — grounded values carry no " +
                "anchor column, so a question whose only restriction is a date will be refused by the door",
        )
    }

    val defaultThresholds =
        ResolverThresholds(
            bind = config.getDouble("resolver.threshold-bind"),
            ambiguityGap = config.getDouble("resolver.threshold-ambiguity-gap"),
            exact = config.getDouble("resolver.threshold-exact"),
            maxOptions = config.getInt("resolver.max-options"),
            strong = config.getDouble("resolver.threshold-strong"),
        )
    val lookupRoundConfig =
        LookupRoundConfig(
            budgetMs = config.getLong("resolver.lookup-budget-ms"),
            maxCandidates = config.getInt("resolver.lookup-max-candidates"),
            broadMaxCandidates = config.getInt("resolver.lookup-broad-max-candidates"),
            maxQueriesPerRound = config.getInt("resolver.lookup-max-queries-per-round"),
        )
    // Snapshot-fed registry over the one-channel seam (RS-24). ✅ Q-7 (Bora, 2026-08-14): the
    // channel is the COMPILED LEXICON ARCHIVE — the same file lex-matcher mounts — when the
    // estate points at one. A per-request `Registry` override still wins for that request.
    //
    // ⚠ No path configured ⇒ the E3-β step-one adapter, which yields an EMPTY vocabulary. That
    // was the silent default for every deployment until now, and its whole problem was that it
    // looked exactly like an estate that had declared nothing. It is still the default — the
    // narrowing that a fed registry causes is an estate's decision — but it is no longer silent.
    val archivePath = config.getString("resolver.lexicon-archive-path").trim()
    val registrySource =
        if (archivePath.isEmpty()) {
            log.warn(
                "resolver.lexicon-archive-path is unset — the declared vocabulary is EMPTY, so the " +
                    "anchored span-proposal path cannot fire and every binding will carry the stub's " +
                    "snapshot hash. Set RESOLVER_LEXICON_ARCHIVE_PATH to the mounted lexicon archive " +
                    "(the same one lex-matcher reads) to feed it.",
            )
            LiveMetadataRegistryAdapter()
        } else {
            LexiconArchiveRegistrySource(
                java.nio.file.Path
                    .of(archivePath),
            )
        }
    val registry = SnapshotRegistry(registrySource, defaultThresholds)
    // ⛑ Projected once at boot so the size is in the log before the first question. An empty
    // vocabulary looks exactly like a small one, which is why this is stated and not inferred.
    runBlocking {
        val loaded = registry.current()
        log.info(
            "declared vocabulary: {} entity type(s), {} anchor(s), snapshot={}",
            loaded.entityTypes.size,
            loaded.entityTypes.sumOf { it.anchors.size },
            loaded.snapshotHash.ifBlank { "<none>" },
        )
    }

    // HMAC resume-token codec (RS-26). Keys come from config so they rotate by
    // key_id without a redeploy; the active key signs, all listed keys verify
    // (rotation window). Key management is chart discipline (S-3).
    val tokenCodec = buildResumeTokenCodec(config)

    val pipeline =
        ResolverPipeline(
            nlpClient,
            fuzzyClient,
            registry,
            siblings = emptyMap(),
            tokenCodec = tokenCodec,
            preps = FrameRolePreps.load(config),
            lookupRounds = LookupRounds(fuzzyClient, lookupRoundConfig),
            grounding = groundingClient,
            defaultPackage = resolverPackage,
        )
    val resolverService = ResolverGrpcService(pipeline, otel)

    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(maxMessageSize)
            .intercept(IncomingCallLoggingInterceptor())
            // TG-P0-F1 — join the caller's trace instead of starting a fresh one. Extracts the
            // W3C `traceparent` golem now injects and makes it current *inside the handler
            // coroutine*, which is why it is a CoroutineContextServerInterceptor and not a plain
            // one: a thread-local would be lost at the first suspension point. Two things follow
            // from it — the resolver's spans hang under the turn, and the Logback appender stamps
            // trace_id on every line the handler logs, so a turn's resolver logs are findable AS
            // that turn. Ordering vs the logging interceptor does not matter; neither reads the
            // other's state.
            .intercept(OtelContextServerInterceptor(otel))
            .addService(resolverService)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("resolver gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    // RG-P6.S1 — the `resolve.bind:v1` MCP door, co-hosted in-process on its own
    // port (RS-22: the service is the door). A second embedded CIO server keeps the
    // MCP streamable-HTTP surface isolated from the health server; the OBO gate is
    // fail-closed when `mcp.require-identity` is on (H-2). Zero LLM below the door.
    val doorPort = config.getInt("mcp.port")
    // Loopback by default (RG-P6 review D): the door must not be reachable off-host
    // unless a deployment explicitly fronts it with an auth-terminating ingress and
    // sets `mcp.host = 0.0.0.0`. The JWT is decoded, not signature-verified, here.
    val doorHost = if (config.hasPath("mcp.host")) config.getString("mcp.host") else "127.0.0.1"
    val requireIdentity = config.getBoolean("mcp.require-identity")
    // Identity-source policy (RG-P6 review A): production trusts ONLY an OBO Bearer
    // token. `mcp.trust-network = true` (dev / behind a trusted terminator) re-opens
    // the `X-User-Id` header, the `user_id` arg, and the `admin:` role convention.
    val trustNetwork = config.hasPath("mcp.trust-network") && config.getBoolean("mcp.trust-network")
    val identityPolicy = if (trustNetwork) IdentityPolicy.PERMISSIVE else IdentityPolicy.TOKEN_ONLY
    val door = ResolveDoor(pipeline::resolve)
    val doorHandler = ResolveDoorHandler(door, requireIdentity, identityPolicy)
    val doorServer =
        embeddedServer(
            factory = CIO,
            rootConfig =
                serverConfig {
                    module {
                        installMcpRequestHeaders()
                        installMcpKtorBase(
                            McpKtorConfig(
                                serviceName = "resolver-door",
                                serverPort = doorPort,
                                connectionIdleTimeoutSeconds = 120,
                            ),
                            // TG-P0-F1: the same SDK the gRPC surface uses. It was
                            // `OpenTelemetry.noop()` here, which was consistent with the rest of
                            // the service only because the rest of the service was noop too.
                            otel,
                        )
                        installResolveDoor(door, doorHandler)
                    }
                },
            configure = {
                connectionIdleTimeoutSeconds = 120
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = doorPort
                        host = doorHost
                    },
                )
            },
        )
    launch {
        doorServer.start(wait = false)
        log.info(
            "resolve door (MCP streamable HTTP) started on {}:{} (require-identity={}, trust-network={})",
            doorHost,
            doorPort,
            requireIdentity,
            trustNetwork,
        )
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "resolver")
                    put("grpc_port", grpcPort)
                    put("llm", false) // RS-23: ZERO LLM — the door line is the determinism line
                },
            )
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutting down resolver gRPC server + resolve door")
        doorServer.stop(1000, 2000)
        grpcServer.shutdown()
        nlpClient.close()
        fuzzyClient.close()
    }
}

/**
 * Build the HMAC resume-token codec from config (S-3 key discipline, RS-26).
 * `resolver.resume-token.keys` is a map key_id → base64url secret (≥32 bytes);
 * `active-key-id` signs. Hardened per RG-P6 review K/L:
 *  - a missing `max-age-seconds` no longer means "never expires" — it falls back to
 *    [DEFAULT_RESUME_MAX_AGE_SECONDS] so a dropped config key cannot silently make
 *    tokens eternal;
 *  - an empty key set is FAIL-CLOSED (the service refuses to boot) unless
 *    `allow-ephemeral-key = true` is set explicitly — the ephemeral per-process key
 *    breaks stateless resume across replicas/restarts, so it is opt-in dev only;
 *  - configured keys shorter than 32 bytes are rejected (weak HMAC secret).
 */
private fun buildResumeTokenCodec(config: Config): ResumeTokenCodec {
    val path = "resolver.resume-token"
    val maxAge =
        if (config.hasPath("$path.max-age-seconds")) {
            config.getLong("$path.max-age-seconds")
        } else {
            DEFAULT_RESUME_MAX_AGE_SECONDS
        }
    if (config.hasPath("$path.keys") && !config.getConfig("$path.keys").isEmpty) {
        val keysConfig = config.getConfig("$path.keys")
        val keys =
            keysConfig
                .root()
                .keys
                .associateWith { keyId ->
                    val secret =
                        java.util.Base64
                            .getUrlDecoder()
                            .decode(keysConfig.getString(keyId))
                    require(secret.size >= MIN_RESUME_KEY_BYTES) {
                        "resolver.resume-token.keys.$keyId is ${secret.size}B; need ≥$MIN_RESUME_KEY_BYTES (RS-26)"
                    }
                    secret
                }
        val active = config.getString("$path.active-key-id")
        log.info("resume-token codec: {} key(s), active={}, max-age={}s", keys.size, active, maxAge)
        return ResumeTokenCodec(keys, active, maxAgeSeconds = maxAge)
    }
    val allowEphemeral = config.hasPath("$path.allow-ephemeral-key") && config.getBoolean("$path.allow-ephemeral-key")
    require(allowEphemeral) {
        "resolver.resume-token.keys is empty and allow-ephemeral-key is not set — refusing to boot on an " +
            "ephemeral per-process key (stateless resume would break across replicas/restarts). Configure a key, " +
            "or set resolver.resume-token.allow-ephemeral-key = true for local dev only."
    }
    val ephemeral =
        java.security.SecureRandom
            .getInstanceStrong()
            .generateSeed(32)
    log.warn("resolver.resume-token.keys unset — using an EPHEMERAL dev key; resume tokens will not survive a restart")
    return ResumeTokenCodec(mapOf("dev" to ephemeral), activeKeyId = "dev", maxAgeSeconds = maxAge)
}

/** Default replay window when `max-age-seconds` is unset — never "no expiry" (review K). */
private const val DEFAULT_RESUME_MAX_AGE_SECONDS = 3600L

/** Minimum configured HMAC secret length (review L). */
private const val MIN_RESUME_KEY_BYTES = 32
