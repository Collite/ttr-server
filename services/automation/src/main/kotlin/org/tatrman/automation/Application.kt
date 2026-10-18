// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
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
import org.tatrman.automation.api.descriptorRoutes
import org.tatrman.automation.api.healthRoutes
import org.tatrman.automation.api.installAutomationErrors
import org.tatrman.automation.config.AutomationConfig
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.automation.ledger.Ledger
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
import java.time.Clock

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
 * database + Flyway (V1 ledger · V2 pgmq · V3 queues) → the pgmq queues checked → the bearer check (S0.3:
 * every route under `/automation` but `/automation/health` answers 401 without a verified token) → Ktor. The
 * bundle load (S0.2), the runner (P1, which resolves `RunAs` at open) and the scheduler (S3.2) join this
 * sequence as they land.
 */
fun Application.module(config: Config) {
    val automation = AutomationConfig.load(config)
    log.info("automation: {}", automation)

    installKtorServerBase(KtorConfigFactory.fromConfig(config, SERVICE, DEFAULT_PORT))

    val openTelemetry =
        createOpenTelemetrySdk(
            OtelEndpointConfig(serviceName = SERVICE, protocol = System.getenv("AUTOMATION_OTEL_PROTOCOL") ?: "grpc"),
            enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
        )

    val db = DatabaseConnection(automation.db).also { it.init() }
    val migrated = migrateLedger(db)
    log.info("automation: ledger migrated ({} migration(s) applied)", migrated)

    val pgmq = Pgmq()
    val created = pgmq.ensure()
    if (created.isNotEmpty()) log.warn("automation: created missing queue(s) {} — V3 should have", created)

    val clock = Clock.offset(Clock.systemUTC(), automation.clockSkew)
    val ledger = Ledger(defaultBudget = automation.budgets.batchDefault, clock = clock)
    val sources = LedgerHealthSources(ledger, pgmq, automation)
    AutomationTelemetry(openTelemetry, queueDepths = sources::queueDepths)

    val callers =
        CallerVerifier(
            JwksBearerVerifier(
                HttpJwksKeySource(automation.auth.jwksUrl),
                issuer = automation.auth.issuer,
                audience = automation.auth.audience,
            ),
        )
    install(Authentication) { AutomationBearerProvider.register(this, callers) }

    install(StatusPages) { installAutomationErrors() }
    routing {
        healthRoutes(sources)
        authenticate(AUTOMATION_AUTH) { descriptorRoutes(paused = { automation.paused }) }
    }

    monitor.subscribe(ApplicationStopping) { db.close() }
}

/** Flyway over schema `automation` (its history table lives there too); answers how many migrations ran. */
fun migrateLedger(db: DatabaseConnection): Int =
    Flyway
        .configure()
        .dataSource(db.getDataSource())
        .schemas("automation")
        .load()
        .migrate()
        .migrationsExecuted

/** The health report's reads, over the ledger. The served bundle and the scheduler arrive with S0.2/S3.2. */
private class LedgerHealthSources(
    private val ledger: Ledger,
    private val pgmq: Pgmq,
    private val automation: AutomationConfig,
) : HealthSources {
    override fun dbReachable(): Boolean = ledger.ping()

    override fun queueDepths(): Map<String, Long> =
        LedgerQueue.entries.associate {
            it.key to
                pgmq.metrics(it).queueLength
        }

    override fun abandoned(): Long = ledger.countAbandoned()

    override fun served(): ServedBundle? = null

    override fun scheduler(): SchedulerHealth? = null

    override fun refreshFailed(): Boolean = false

    override fun paused(): Boolean = automation.paused
}
