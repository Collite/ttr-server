// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health

import org.tatrman.health.api.healthRoutes
import org.tatrman.health.api.statusRoutes
import org.tatrman.health.config.ConfigLoader
import org.tatrman.health.config.HealthConfig
import org.tatrman.health.content.ContentService
import org.tatrman.health.service.HealthCheckService
import org.tatrman.health.status.BuildInfo
import org.tatrman.health.status.ModelFingerprintProbe
import org.tatrman.health.status.StatusService
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

private val logger = LoggerFactory.getLogger("main")

fun main() {
    val config = ConfigFactory.load()
    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "health-check-service",
            protocol = System.getenv("HEALTH_CHECK_SERVICE_OTEL_PROTOCOL") ?: "grpc",
        ),
        enabled = config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled"),
    )
    val serverConfig =
        KtorConfigFactory.fromConfig(
            config = config,
            defaultServiceName = "health-check-service",
            defaultPort = 7000,
        )
    val healthConfig = ConfigLoader.load()
    KtorServerBootstrap.createServer(serverConfig) { module(healthConfig, serverConfig, config) }.start(wait = true)
}

fun Application.module(
    healthConfig: HealthConfig,
    serverConfig: KtorServerConfig,
    config: Config,
) {
    logger.info("Starting Health Check service")
    installKtorServerBase(serverConfig)

    val service = HealthCheckService(healthConfig)
    val content =
        ContentService(
            healthConfig.technologies.mapNotNull { (key, technology) -> technology.status?.let { key to it } }.toMap(),
        )

    // FO-P5.S2 (FO-28): the open-tier status surface — Server version + model fingerprint + rollup.
    val fingerprintUrl =
        if (config.hasPath("status-page.model-fingerprint-url")) {
            config.getString("status-page.model-fingerprint-url")
        } else {
            "http://metadata:7260/status"
        }
    val fingerprintProbe = ModelFingerprintProbe(fingerprintUrl, HttpClient(Apache))
    val statusService =
        StatusService(
            serverVersion = BuildInfo.serverVersion,
            modelFingerprint = fingerprintProbe::fetch,
            rollup = { service.checkAllHealth(100) },
        )

    routing {
        healthRoutes(service, content)
        statusRoutes(statusService)
    }
}
