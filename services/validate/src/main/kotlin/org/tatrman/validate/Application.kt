// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.validate.client.GrpcMetadataClient
import org.tatrman.validate.client.KtorLlmGatewayClient
import org.tatrman.validate.client.LlmGatewayClient
import org.tatrman.validate.client.LocalPolicyClient
import org.tatrman.validate.client.MetadataClient
import org.tatrman.validate.client.SecurityClient
import org.tatrman.validate.client.StaticMetadataClient
import org.tatrman.validate.grpc.ValidateServiceImpl
import org.tatrman.validate.health.DependencyMonitor
import org.tatrman.validate.policy.GrpcPolicyMetadataClient
import org.tatrman.validate.policy.PolicyConfigLoader
import org.tatrman.validate.policy.PolicyEngine
import org.tatrman.validate.policy.PolicyRegistry
import org.tatrman.validate.roles.BearerRoleSource
import org.tatrman.validate.roles.KtorWhoisRoleLookup
import org.tatrman.validate.roles.RoleSource
import org.tatrman.validate.roles.WhoisRoleSource
import org.tatrman.validate.stages.LlmGuard
import org.tatrman.validate.stages.RuleEnforcer
import org.tatrman.validate.stages.SecurityApplier
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.logging.IncomingCallLoggingInterceptor
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

private val log = LoggerFactory.getLogger("org.tatrman.validate.Application")

fun main() {
    val config = loadValidateConfig()
    val serverConfig = KtorConfigFactory.fromConfig(config, "validate", 7285)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

/**
 * Base config (application.conf, which includes `policies/policies.conf`) with an optional
 * external policy override: when `VALIDATE_POLICIES_FILE` points at a readable HOCON file (e.g. a
 * k8s ConfigMap mount), its `validate.policies` replaces the built-in default set — no rebuild.
 */
private fun loadValidateConfig(): Config {
    val base = ConfigFactory.load()
    val override = System.getenv("VALIDATE_POLICIES_FILE")?.trim().orEmpty()
    if (override.isEmpty()) return base
    val file = java.io.File(override)
    if (!file.isFile) {
        log.warn("VALIDATE_POLICIES_FILE='{}' is not a readable file — using built-in policies", override)
        return base
    }
    log.info("Loading external Validate policies from {}", override)
    return ConfigFactory.parseFile(file).withFallback(base).resolve()
}

fun Application.module(config: Config) {
    installKtorServerBase(KtorConfigFactory.fromConfig(config, "validate", 7285))

    // OTel SDK init: configures OTLP trace/metric/log exporters AND installs the bridge
    // into the Logback OpenTelemetryAppender so all SLF4J logs are forwarded to OTLP → Alloy → Loki.
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "validate",
            protocol = System.getenv("VALIDATE_OTEL_PROTOCOL") ?: "grpc",
        ),
        enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
    )

    val useFixture =
        config.hasPath("validate.use-fixture-model") &&
            config.getBoolean("validate.use-fixture-model")

    val securityClient = pickSecurityClient(config, useFixture)
    val metadataClient = pickMetadataClient(config, useFixture)

    val securityApplier = SecurityApplier(securityClient)
    val defaultTopN = config.getInt("validate.default-top-n")
    // Absent = the default: a caller can ask for fewer rows, never more (the pre-max-top-n rule).
    val maxTopN =
        if (config.hasPath("validate.max-top-n")) config.getInt("validate.max-top-n") else defaultTopN
    if (maxTopN < defaultTopN) {
        log.warn(
            "validate.max-top-n ({}) is below validate.default-top-n ({}); every answer is capped at {}",
            maxTopN,
            defaultTopN,
            maxTopN,
        )
    }
    // BI (C-10) — per-role ceilings; a malformed table fails the boot (RuleEnforcer.parseMaxByRole).
    val maxTopNByRole =
        RuleEnforcer.parseMaxByRole(
            if (config.hasPath("validate.max-top-n-by-role")) config.getString("validate.max-top-n-by-role") else "",
        )
    maxTopNByRole.filterValues { it <= maxTopN }.keys.forEach { role ->
        log.warn(
            "validate.max-top-n-by-role: {}'s ceiling ({}) is not above max-top-n ({}); it changes nothing",
            role,
            maxTopNByRole.getValue(role),
            maxTopN,
        )
    }
    if (maxTopNByRole.isNotEmpty()) log.info("Row ceilings by role: {}", maxTopNByRole)
    val ruleEnforcer = RuleEnforcer(serviceDefault = defaultTopN, serviceMax = maxTopN, maxByRole = maxTopNByRole)
    val llmGuardEnabled = config.getBoolean("validate.llm-guard.enabled")
    val llmGuardGateway = buildLlmGatewayClient(config)
    val llmGuardModel =
        if (config.hasPath("validate.llm-guard.model")) {
            config.getString("validate.llm-guard.model")
        } else {
            LlmGuard.DEFAULT_MODEL
        }
    val llmGuardFailurePosture =
        if (config.hasPath("validate.llm-guard.failure-posture")) {
            runCatching {
                LlmGuard.FailurePosture.valueOf(
                    config.getString("validate.llm-guard.failure-posture").uppercase(),
                )
            }.getOrElse { LlmGuard.FailurePosture.FAIL_CLOSED }
        } else {
            LlmGuard.FailurePosture.FAIL_CLOSED
        }
    val llmGuard =
        LlmGuard(
            enabled = llmGuardEnabled,
            gateway = llmGuardGateway,
            model = llmGuardModel,
            failurePosture = llmGuardFailurePosture,
        )

    val adminRole =
        if (config.hasPath("validate.security-bypass.admin-role")) {
            config.getString("validate.security-bypass.admin-role")
        } else {
            "query-platform-admin"
        }
    // Fork Stage 5.3 — role source: `bearer` (default; Phase-3 posture, no whois dependency) or
    // `whois` (opt-in ERP role-hierarchy enrichment). Identity stays bearer-only at the
    // query-mcp edge either way; whois only widens the role set, never asserts identity.
    val roleSourceMode =
        if (config.hasPath("validate.roleSource")) config.getString("validate.roleSource").lowercase() else "bearer"
    val roleSource: RoleSource =
        when (roleSourceMode) {
            "whois" -> {
                val whoisBaseUrl =
                    config
                        .takeIf { it.hasPath("validate.whois.baseUrl") }
                        ?.getString("validate.whois.baseUrl") ?: "http://whois:7110"
                val ttl =
                    config
                        .takeIf { it.hasPath("validate.whois.cacheTtlSeconds") }
                        ?.getLong("validate.whois.cacheTtlSeconds") ?: 300L
                log.info("Validate role source = whois (enrichment baseUrl={}, cacheTtl={}s)", whoisBaseUrl, ttl)
                WhoisRoleSource(KtorWhoisRoleLookup(whoisBaseUrl), cacheTtlSeconds = ttl)
            }
            else -> {
                log.info("Validate role source = bearer (default; no whois dependency)")
                BearerRoleSource()
            }
        }
    val validateService =
        ValidateServiceImpl(
            securityApplier = securityApplier,
            ruleEnforcer = ruleEnforcer,
            llmGuard = llmGuard,
            metadataClient = metadataClient,
            adminRole = adminRole,
            roleSource = roleSource,
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
            .intercept(IncomingCallLoggingInterceptor())
            .addService(validateService)
            .apply { if (reflectionEnabled) addService(ProtoReflectionServiceV1.newInstance()) }
            .build()

    launch {
        grpcServer.start()
        log.info("Validate gRPC server started on port {} (reflection={})", grpcPort, reflectionEnabled)
        grpcServer.awaitTermination()
    }

    // Readiness gate: Metadata (model graph) is the one hard dependency — policy evaluation is
    // in-process since the 3.2 fold, so there is no sql-security hop to probe. The monitor probes
    // Metadata on a background scope with exponential backoff and drives /ready so K8s holds traffic
    // until it recovers. Fixture boots have no channel, so the probe reports ready immediately.
    val dependencyMonitor = buildDependencyMonitor(config, metadataClient)
    val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    dependencyMonitor.start(monitorScope)

    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") {
            if (useFixture || dependencyMonitor.ready()) {
                call.respond(buildJsonObject { put("status", "UP") })
            } else {
                call.respond(
                    io.ktor.http.HttpStatusCode.ServiceUnavailable,
                    buildJsonObject {
                        put("status", "NOT_READY")
                        putJsonArray("dependencies_down") { dependencyMonitor.down().forEach { add(it) } }
                    },
                )
            }
        }
        get("/status") {
            val version = runCatching { metadataClient.currentVersion() }.getOrDefault("")
            call.respond(
                buildJsonObject {
                    put("service", "validate")
                    put("grpc_port", grpcPort)
                    put("metadata_version", version)
                    put("llm_guard_enabled", llmGuardEnabled)
                    put("default_top_n", defaultTopN)
                    put("max_top_n", maxTopN)
                    putJsonObject("max_top_n_by_role") { maxTopNByRole.forEach { (role, rows) -> put(role, rows) } }
                    put("ready", dependencyMonitor.ready())
                    putJsonObject("dependencies") {
                        dependencyMonitor.statuses().forEach { (name, up) -> put(name, up) }
                    }
                },
            )
        }
    }

    monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
        log.info("Shutting down Validate gRPC server")
        monitorScope.cancel()
        grpcServer.shutdown()
        if (securityClient is AutoCloseable) securityClient.close()
        if (metadataClient is AutoCloseable) metadataClient.close()
        llmGuardGateway?.close()
    }
}

private fun buildDependencyMonitor(
    config: Config,
    metadataClient: MetadataClient,
): DependencyMonitor {
    fun longOr(
        path: String,
        default: Long,
    ): Long = if (config.hasPath(path)) config.getLong(path) else default

    val pollIntervalMs = longOr("validate.dependency-monitor.poll-interval-seconds", 30L) * 1000
    val backoffBaseMs = longOr("validate.dependency-monitor.backoff-base-ms", 1_000L)
    val backoffMaxMs = longOr("validate.dependency-monitor.backoff-max-ms", 60_000L)
    // Metadata (model graph) is the only hard readiness dependency. Since the
    // Stage 3.2 fold, the policy engine runs in-process (no sql-security service
    // to probe), so there is no security dependency in the gate.
    return DependencyMonitor(
        dependencies =
            listOf(
                DependencyMonitor.Dependency("metadata") { metadataClient.probeReady() },
            ),
        pollIntervalMs = pollIntervalMs,
        backoffBaseMs = backoffBaseMs,
        backoffMaxMs = backoffMaxMs,
    )
}

private fun buildLlmGatewayClient(config: Config): LlmGatewayClient? {
    if (!config.hasPath("validate.llm-guard.gateway-url")) return null
    val baseUrl = config.getString("validate.llm-guard.gateway-url").trim()
    if (baseUrl.isEmpty()) return null
    val timeoutMs =
        if (config.hasPath("validate.llm-guard.timeout-ms")) {
            config.getLong("validate.llm-guard.timeout-ms")
        } else {
            10_000L
        }
    val apiKey =
        if (config.hasPath("validate.llm-guard.api-key")) {
            config.getString("validate.llm-guard.api-key").takeIf { it.isNotBlank() }
        } else {
            null
        }
    log.info("LlmGuard gateway configured at {} (timeout {}ms)", baseUrl, timeoutMs)
    return KtorLlmGatewayClient(baseUrl = baseUrl, timeoutMs = timeoutMs, apiKey = apiKey)
}

private fun pickSecurityClient(
    config: Config,
    useFixture: Boolean,
): SecurityClient {
    if (useFixture) {
        log.warn(
            "Validate booting with fixture policy client (validate.use-fixture-model = true) — no row-level " +
                "policies applied. Production deployments leave this false.",
        )
        return SecurityClient { req ->
            org.tatrman.security.v1
                .EvaluatePoliciesResponse
                .newBuilder()
                .setContext(req.context)
                .build()
        }
    }
    // Stage 3.2 fold: policy evaluation is in-process. Load policies from HOCON
    // (validate.policies) and key on the bearer roles carried in the request context.
    val registry = PolicyRegistry(PolicyConfigLoader.load(config))
    val policyMetadata =
        GrpcPolicyMetadataClient(
            host = config.getString("metadata.host"),
            port = config.getInt("metadata.port"),
        )
    log.info("Validate policy engine loaded {} in-process policies", registry.size())
    return LocalPolicyClient(PolicyEngine(registry, policyMetadata))
}

private fun pickMetadataClient(
    config: Config,
    useFixture: Boolean,
): MetadataClient {
    if (useFixture) {
        return StaticMetadataClient(version = "boot-fixture-v0")
    }
    val deadlineSeconds =
        if (config.hasPath("metadata.deadline-seconds")) {
            config.getLong("metadata.deadline-seconds")
        } else {
            30L
        }
    return GrpcMetadataClient(
        host = config.getString("metadata.host"),
        port = config.getInt("metadata.port"),
        deadlineSeconds = deadlineSeconds,
    )
}
