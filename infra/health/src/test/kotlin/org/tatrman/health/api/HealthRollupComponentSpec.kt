// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get as wmGet
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import org.tatrman.health.config.HealthConfig
import org.tatrman.health.config.TechnologyConfig
import org.tatrman.health.content.ContentService
import org.tatrman.health.service.HealthCheckService

/**
 * Component-tier spec (mocked check targets): the roll-up endpoint aggregates per-service
 * status. One target up (WireMock 200), one configured-but-down (unreachable port) → the
 * roll-up reports 1 healthy / 1 unhealthy and an overall "unhealthy" at the 100% threshold.
 * True e2e against live kantheon services is deferred to the integration suite.
 */
class HealthRollupComponentSpec :
    StringSpec({

        fun startWiremock(): WireMockServer =
            WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }

        fun serviceWith(upUrl: String): HealthCheckService =
            HealthCheckService(
                HealthConfig(
                    technologies =
                        mapOf(
                            "up-svc" to TechnologyConfig(type = "native", url = upUrl, healthEndpoint = "/health"),
                            "down-svc" to
                                TechnologyConfig(
                                    type = "native",
                                    url = "http://localhost:1",
                                    healthEndpoint = "/health",
                                    timeout = 500,
                                ),
                        ),
                ),
            )

        "GET /health/all reports the mixed aggregate and 503 at the 100% threshold" {
            val wm = startWiremock()
            try {
                wm.stubFor(wmGet(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(200)))
                val service = serviceWith("http://localhost:${wm.port()}")
                testApplication {
                    application { mountHealth(service) }
                    val resp = client.get("/health/all")
                    resp.status shouldBe HttpStatusCode.ServiceUnavailable
                    val body = resp.bodyAsText()
                    body shouldContain "\"total\":2"
                    body shouldContain "\"healthy\":1"
                    body shouldContain "\"unhealthy\":1"
                    body shouldContain "\"status\":\"unhealthy\""
                }
            } finally {
                wm.stop()
            }
        }

        "GET /health/{technology} resolves a single up and a single down target" {
            val wm = startWiremock()
            try {
                wm.stubFor(wmGet(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(200)))
                val service = serviceWith("http://localhost:${wm.port()}")
                testApplication {
                    application { mountHealth(service) }
                    client.get("/health/up-svc").status shouldBe HttpStatusCode.OK
                    client.get("/health/down-svc").status shouldBe HttpStatusCode.ServiceUnavailable
                    client.get("/health/no-such-svc").status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                wm.stop()
            }
        }
    })

/** Test-local wiring: ContentNegotiation + the health routes, in one Application extension. */
private fun Application.mountHealth(service: HealthCheckService) {
    install(ContentNegotiation) { json() }
    healthRoutes(service, ContentService(emptyMap()))
}
