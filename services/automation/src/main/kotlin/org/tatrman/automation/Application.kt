// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.tatrman.automation.api.HealthSources
import org.tatrman.automation.api.SchedulerHealth
import org.tatrman.automation.api.ServedBundle
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.DescriptorSources
import org.tatrman.automation.api.descriptorRoutes
import org.tatrman.automation.api.runRoutes
import org.tatrman.automation.api.healthRoutes
import org.tatrman.automation.api.installAutomationErrors
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.bundle.Bundles
import org.tatrman.automation.bundle.ExecutorType
import org.tatrman.automation.bundle.GitBundleSource
import org.tatrman.automation.bundle.GrpcMetadataStatus
import org.tatrman.automation.bundle.Registry
import org.tatrman.automation.bundle.MetadataHandshake
import org.tatrman.automation.config.AutomationConfig
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.runner.GrpcQueryRun
import org.tatrman.automation.runner.RunRuntime
import org.tatrman.automation.staging.StagingStores
import org.tatrman.automation.ledger.LedgerBundles
import org.tatrman.automation.obs.AutomationTelemetry
import org.tatrman.automation.queue.LedgerQueue
import org.tatrman.automation.queue.Pgmq
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.installKtorServerBase
import shared.libs.db.common.DatabaseConnection
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Clock
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("org.tatrman.automation.Application")

private const val SERVICE = "automation"
private const val DEFAULT_PORT = 7131

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, SERVICE, DEFAULT_PORT)
    KtorServerBootstrap.createServer(serverConfig) { module(config) }.start(wait = true)
}

/**
 * The bootstrap (AU-server S0.1): config (fails at load on a missing required key) → OTel → the ledger's
 * database + Flyway (V1–V7 ledger, queues, dispatch, subjects and release fences) → queues → bearer
 * check (S0.3: every route under `/automation` but `/automation/health` answers 401 without a verified token) →
 * Ktor with the preview runtime and durable readers → the served bundle loaded in the background (S0.2), then
 * polled. One process owns execution contexts; the deployment uses one replica and Recreate updates.
 * The scheduler remains a later phase.
 */
fun Application.module(config: Config) {
    val automation = AutomationConfig.load(config)
    log.info("automation: {}", automation)
    if (automation.approvalsOff) {
        log.warn(
            "automation: DEVELOPMENT MODE — approval checks are OFF; opens approve current fingerprints " +
                "and record this bypass in the ledger's opening protocol",
        )
    }

    installKtorServerBase(KtorConfigFactory.fromConfig(config, SERVICE, DEFAULT_PORT))

    val openTelemetry =
        createOpenTelemetrySdk(
            OtelEndpointConfig(serviceName = SERVICE, protocol = System.getenv("AUTOMATION_OTEL_PROTOCOL") ?: "grpc"),
            enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
        )

    // Application owns staging; runtime shutdown must finish before dependencies close after HTTP drain.
    var execution: RunRuntime? = null
    val staging = StagingStores.open(automation.staging)
    monitor.subscribe(ApplicationStopped) {
        // Also covers failed startup before runtime construction and a missed/retried Stopping cleanup.
        runBlocking(Dispatchers.Default) { execution?.shutdown() }
        staging.close()
    }

    val db = DatabaseConnection(automation.db).also { it.init() }
    val migrated = migrateLedger(db)
    log.info("automation: ledger migrated ({} migration(s) applied)", migrated)

    val pgmq = Pgmq()
    val created = pgmq.ensure()
    if (created.isNotEmpty()) log.warn("automation: created missing queue(s) {} — V3 should have", created)

    val clock = Clock.offset(Clock.systemUTC(), automation.clockSkew)
    // The telemetry first: the ledger and the registry count into it (§10).
    val telemetry = AutomationTelemetry(openTelemetry, queueDepths = pgmq::depths)
    val ledger =
        Ledger(defaultBudget = automation.budgets.batchDefault, clock = clock, abandoned = telemetry::abandoned)
    val ledgerBundles = LedgerBundles()
    val bundles =
        Bundles(
            servedBranch = automation.bundleGit.branch,
            previewsConfig = automation.previews,
            source = GitBundleSource(automation.bundleGit, Path.of(automation.bundleGit.checkoutDir)),
            loader = BundleLoader(AuthorMap(automation.auth.authorMap), clock),
            book = ledgerBundles,
            clock = clock,
            refreshed = telemetry::refreshed,
        )
    val sources = LedgerHealthSources(ledger, pgmq, automation, bundles)

    if (automation.auth.audience == null) {
        log.warn(
            "automation: auth.audience is not set — a token the realm issued to ANY client is accepted; " +
                "set it once the realm has this service's client",
        )
    }
    val callers =
        CallerVerifier(
            JwksBearerVerifier(
                HttpJwksKeySource(automation.auth.jwksUrl),
                issuer = automation.auth.issuer,
                audience = automation.auth.audience,
            ),
        )
    install(Authentication) { AutomationBearerProvider.register(this, callers) }
    val metadataStatus = GrpcMetadataStatus(automation.door.metadataUrl)
    val tokens = ServiceTokens.keycloak(automation.connections, automation.auth.tokenUrl)
    val query = GrpcQueryRun(automation.door.queryUrl)
    // Durable readers and descriptors still need dependencies during HTTP drain; close them only afterwards.
    monitor.subscribe(ApplicationStopped) {
        runBlocking(Dispatchers.Default) { execution?.shutdown() }
        try {
            query.close()
        } finally {
            try {
                metadataStatus.close()
            } finally {
                db.close()
            }
        }
    }
    val runtime =
        RunRuntime(
            automation,
            { bundles.served },
            ledgerBundles,
            tokens,
            ledger,
            this,
            clock,
            query,
            staging,
        ).also { execution = it }
    // Fence new execution and join its workers/sessions before HTTP drain and dependency cleanup.
    // Use a worker dispatcher: shutdown must not depend on a blocked engine/event-loop thread.
    monitor.subscribe(ApplicationStopping) { runBlocking(Dispatchers.Default) { runtime.shutdown() } }

    install(StatusPages) { installAutomationErrors() }
    routing {
        healthRoutes(sources)
        authenticate(AUTOMATION_AUTH) {
            runRoutes(runtime)
            descriptorRoutes(
                DescriptorSources(
                    bundles = bundles,
                    registry = Registry(ExecutorType.builtin()),
                    approvals = ledgerBundles,
                    tokens = tokens,
                    metadata = MetadataHandshake(metadataStatus, automation.bundleGit.metadataSource),
                    paused = { automation.paused },
                    refreshRoles = automation.auth.refreshRoles,
                ),
            )
        }
    }

    // The served bundle: loaded at start (a failure leaves nothing served, the service up), then by the poll, the
    // CI call on merge and the manual POST /refresh (D-74). No repo configured = nothing is served.
    if (automation.bundleGit.url != null) {
        launch {
            refreshQuietly(bundles)
            val every = automation.bundleGit.pollSeconds
            while (every > 0) {
                delay(every * 1000)
                refreshQuietly(bundles)
            }
        }
    }
}

/** Flyway over schema `automation` (its history table lives there too); answers how many migrations ran. */
fun migrateLedger(db: DatabaseConnection): Int = migrateLedger(db.getDataSource())

fun migrateLedger(dataSource: DataSource): Int =
    Flyway
        .configure()
        .dataSource(dataSource)
        .schemas("automation")
        .load()
        .migrate()
        .migrationsExecuted

/**
 * The start load and the poll: a failed refresh is logged, never thrown — an exception escaping here would end the
 * poll loop for the life of the pod. [Bundles] has already marked a served-branch failure for health.
 */
private suspend fun refreshQuietly(bundles: Bundles) {
    try {
        bundles.refresh()
    } catch (e: AutomationException) {
        log.warn("automation: refresh — {} {}", e.code.code, e.message)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("automation: refresh failed; the poll goes on", e)
    }
}

/** Each ledger queue's depth, keyed as health and the `queue` label name it. */
private fun Pgmq.depths(): Map<String, Long> = LedgerQueue.entries.associate { it.key to metrics(it).queueLength }

/** The health report's reads, over the ledger and the bundle registry. The scheduler arrives with S3.2. */
private class LedgerHealthSources(
    private val ledger: Ledger,
    private val pgmq: Pgmq,
    private val automation: AutomationConfig,
    private val bundles: Bundles,
) : HealthSources {
    override fun dbReachable(): Boolean = ledger.ping()

    override fun queueDepths(): Map<String, Long> = pgmq.depths()

    override fun abandoned(): Long = ledger.countAbandoned()

    override fun served(): ServedBundle? =
        bundles.served?.let {
            ServedBundle(it.id, it.commit, it.modelFp, it.loadedAt.toString())
        }

    override fun scheduler(): SchedulerHealth? = null

    override fun refreshFailed(): Boolean = bundles.refreshFailed

    override fun paused(): Boolean = automation.paused
}
