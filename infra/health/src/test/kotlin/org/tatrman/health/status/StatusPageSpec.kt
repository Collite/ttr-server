// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.status

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import org.tatrman.health.api.statusRoutes
import org.tatrman.health.service.AllHealthResponse
import org.tatrman.health.service.HealthSummary
import org.tatrman.health.service.TechnologyStatus

/**
 * FO-P5.S2.T1 — the open-tier status surface (FO-28). A minimal human page (`GET /`) + its JSON twin
 * (`GET /status`) show the Server version, the model fingerprint, and the per-service health rollup.
 * The open Server has NO admin app: the surface is asserted READ-ONLY (GET-only) — the executable form
 * of FO-28's "no admin app is a documented stance, not a gap".
 */
class StatusPageSpec :
    StringSpec({

        val healthyRollup =
            AllHealthResponse(
                status = "healthy",
                summary = HealthSummary(total = 2, healthy = 2, unhealthy = 0),
                technologies =
                    listOf(
                        TechnologyStatus(name = "metadata", status = "healthy"),
                        TechnologyStatus(name = "query", status = "healthy"),
                    ),
            )

        fun svc(
            version: String = "1.2.3",
            fingerprint: String? = "model@abc123",
            rollup: AllHealthResponse = healthyRollup,
        ) = StatusService(
            serverVersion = version,
            modelFingerprint = { fingerprint },
            rollup = { rollup },
        )

        "GET /status returns the server version, model fingerprint and service rollup as JSON" {
            testApplication {
                application { mountStatus(svc()) }
                val resp = client.get("/status")
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "\"serverVersion\":\"1.2.3\""
                body shouldContain "\"modelFingerprint\":\"model@abc123\""
                body shouldContain "\"metadata\""
                body shouldContain "\"query\""
            }
        }

        "GET / renders a minimal HTML page with version, fingerprint and services" {
            testApplication {
                application { mountStatus(svc()) }
                val resp = client.get("/")
                resp.status shouldBe HttpStatusCode.OK
                resp.headers[HttpHeaders.ContentType]!! shouldContain "text/html"
                val html = resp.bodyAsText()
                html shouldContain "Tatrman Server"
                html shouldContain "1.2.3"
                html shouldContain "model@abc123"
                html shouldContain "metadata"
                // The page states the no-management-app stance (FO-28).
                html shouldContain "Read-only"
            }
        }

        "a missing model fingerprint degrades softly (page still renders, JSON is null)" {
            testApplication {
                application { mountStatus(svc(fingerprint = null)) }
                client.get("/status").bodyAsText() shouldContain "\"modelFingerprint\":null"
                client.get("/").bodyAsText() shouldContain "unavailable"
            }
        }

        "the open status surface exposes NO admin verbs — it is GET-only (FO-28)" {
            testApplication {
                application { mountStatus(svc()) }
                // No mutating route is registered on the open surface.
                client.post("/status").status shouldBe HttpStatusCode.MethodNotAllowed
                client.put("/status").status shouldBe HttpStatusCode.MethodNotAllowed
                client.delete("/status").status shouldBe HttpStatusCode.MethodNotAllowed
                client.post("/").status shouldBe HttpStatusCode.MethodNotAllowed
                // And the page advertises no admin affordance.
                client.get("/").bodyAsText() shouldNotContain "admin"
            }
        }
    })

/** Test-local wiring: ContentNegotiation + the status routes, in one Application extension. */
private fun Application.mountStatus(status: StatusService) {
    install(ContentNegotiation) { json() }
    statusRoutes(status)
}
