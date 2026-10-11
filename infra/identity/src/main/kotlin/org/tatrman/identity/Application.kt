// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.tatrman.keycloak.auth.CachingTokenProvider
import org.tatrman.keycloak.auth.KeycloakTokenProvider
import org.tatrman.identity.client.ErpClient
import org.tatrman.identity.client.KeycloakClient
import org.tatrman.identity.client.UserSourceClient
import org.tatrman.identity.domain.UserSource
import org.tatrman.identity.repository.UserRepositoryDb
import org.tatrman.identity.repository.UserRepositoryJson
import org.tatrman.identity.repository.UserRepositoryPort
import org.tatrman.identity.routes.BundleHandler
import org.tatrman.identity.routes.configureRouting
import org.tatrman.identity.sync.WhoisSyncService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import shared.libs.db.common.DatabaseConnection
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    val config = ConfigFactory.load()
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "whois",
            protocol = System.getenv("WHOIS_OTEL_PROTOCOL") ?: "grpc",
        ),
        enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
    )
    val serverConfig = KtorConfigFactory.fromConfig(config, "whois", 7103)
    KtorServerBootstrap.createServer(serverConfig) { module(config, serverConfig) }.start(wait = true)
}

fun Application.module(
    config: Config,
    serverConfig: KtorServerConfig,
) {
    val logger = LoggerFactory.getLogger("WhoisApplication")

    installKtorServerBase(serverConfig)

    install(io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry) {
        setOpenTelemetry(
            io.opentelemetry.api.OpenTelemetry
                .noop(),
        )
    }

    val whoisRepoType = config.getString("whois.repository.type")
    val userRepository: UserRepositoryPort =
        when (whoisRepoType) {
            "db" -> {
                logger.info("Initializing database-backed UserRepository")
                val dbConnection = DatabaseConnection.fromConfig(config, "who-is-database")
                dbConnection.init()
                logger.info("Running Flyway migrations...")
                val flyway =
                    Flyway
                        .configure()
                        .dataSource(dbConnection.getDataSource())
                        .load()
                flyway.migrate()
                logger.info("Flyway migrations completed")
                UserRepositoryDb(dbConnection)
            }
            else -> {
                logger.info("Initializing JSON-backed UserRepository")
                val jsonRepo = UserRepositoryJson(config)
                jsonRepo.load()
                jsonRepo
            }
        }

    val syncJobs = mutableMapOf<UserSource, Job>()
    if (whoisRepoType == "db") {
        logger.info("Initializing sync components...")

        val keycloakTokenProvider =
            KeycloakTokenProvider.fromConfig(config, "keycloak")
        val cachingTokenProvider = CachingTokenProvider.create(keycloakTokenProvider)

        val keycloakTimeout =
            try {
                config.getLong("keycloak.timeout")
            } catch (e: Exception) {
                30_000L
            }
        val keycloakClient = KeycloakClient.fromConfig(config, cachingTokenProvider, "keycloak", keycloakTimeout)
        // Null while `erp-database.queries` is unset: the ERP source is then skipped (ErpClient logs which keys).
        val erpClient = ErpClient.fromConfig(config, "erp-database")

        val clients =
            buildMap<UserSource, UserSourceClient> {
                put(UserSource.KEYCLOAK, keycloakClient)
                erpClient?.let { put(UserSource.ERP, it) }
            }

        val userRepositoryDb = userRepository as UserRepositoryDb
        val syncConfig = WhoisSyncService.loadSyncConfig(config)
        val syncService = WhoisSyncService(clients, userRepositoryDb, syncConfig)

        for (source in UserSource.entries) {
            val sourcePath = source.name.lowercase()
            val intervalSeconds =
                try {
                    config.getLong("whois.sync.$sourcePath.interval-seconds")
                } catch (e: Exception) {
                    0L
                }

            val sourceConfig = syncConfig[source]
            if (sourceConfig == null || !sourceConfig.enabled) {
                logger.info("Sync disabled for source: {}", source)
                continue
            }

            CoroutineScope(Dispatchers.IO)
                .launch {
                    if (intervalSeconds > 0) {
                        logger.info("Running initial sync for {}...", source)
                        syncService.sync(source)
                        logger.info(
                            "Initial sync for {} completed. Starting periodic sync every {} seconds",
                            source,
                            intervalSeconds,
                        )
                        while (coroutineContext.isActive) {
                            delay((intervalSeconds * 1000).milliseconds)
                            syncService.sync(source)
                        }
                    } else {
                        logger.info("Sync disabled for {} (interval <= 0)", source)
                    }
                }.also { syncJobs[source] = it }
        }
    }

    val opaFilePath = config.getString("whois.opaFilePath")
    val bundleHandler = BundleHandler(userRepository, File(opaFilePath))

    routing {
        configureRouting(userRepository, bundleHandler)
    }

    environment.monitor.subscribe(ApplicationStopping) {
        logger.info("Application stopping, shutting down...")
        syncJobs.values.forEach { it.cancel() }
        if (whoisRepoType == "db") {
            logger.info("Closing database connections...")
        }
        logger.info("Shutdown complete")
    }
}
