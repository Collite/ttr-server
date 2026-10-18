// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** `GET /automation/health` (AU contracts §1.1, D-72's hardcoded set) and the two probes. */
class HealthRoutesSpec :
    StringSpec({
        "a healthy ledger answers UP with the contract's keys, queues keyed by their short names" {
            val report = healthReport(FakeSources())
            report.status shouldBe "UP"
            report.queues shouldBe mapOf("runs" to 0L, "tasks" to 2L, "windows" to 0L, "signals" to 1L)
            report.abandoned shouldBe 0L
        }

        "abandoned runs are counted but never degrade the status (the ledger keeps them forever)" {
            healthReport(FakeSources(abandoned = 3)).status shouldBe "UP"
        }

        "a failed /refresh degrades" {
            healthReport(FakeSources(refreshFailed = true)).status shouldBe "DEGRADED"
        }

        "a stopped scheduler degrades; no scheduler in the build (before S3.2) does not" {
            healthReport(FakeSources(scheduler = SchedulerHealth(alive = false, lastTick = "t"))).status shouldBe
                "DEGRADED"
            val noScheduler = healthReport(FakeSources(scheduler = null))
            noScheduler.status shouldBe "UP"
            noScheduler.scheduler shouldBe SchedulerHealth(alive = false, lastTick = null)
        }

        "an unreachable ledger is DOWN, with no counts it cannot read" {
            val report = healthReport(FakeSources(db = false))
            report.status shouldBe "DOWN"
            report.queues shouldBe emptyMap()
            report.abandoned shouldBe null
        }

        "the routes: /automation/health 200 or 503 with the D-72 body, /health always UP, /ready follows the ledger" {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { healthRoutes(FakeSources(paused = true)) }
                }
                val health = client.get("/automation/health")
                health.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(health.bodyAsText()).jsonObject
                body.keys shouldContainExactly
                    setOf("status", "served", "scheduler", "queues", "abandoned", "refreshFailed", "paused")
                body["served"] shouldBe JsonNull
                body["paused"] shouldBe JsonPrimitive(true)
                client.get("/health").status shouldBe HttpStatusCode.OK
                client.get("/ready").status shouldBe HttpStatusCode.OK
            }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { healthRoutes(FakeSources(db = false)) }
                }
                client.get("/automation/health").status shouldBe HttpStatusCode.ServiceUnavailable
                client.get("/health").status shouldBe HttpStatusCode.OK
                client.get("/ready").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        "a source that throws answers DOWN rather than a 500" {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { healthRoutes(FakeSources(queueFailure = true)) }
                }
                val response = client.get("/automation/health")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"] shouldBe JsonPrimitive("DOWN")
            }
        }
    })

private class FakeSources(
    private val db: Boolean = true,
    private val abandoned: Long = 0,
    private val refreshFailed: Boolean = false,
    private val scheduler: SchedulerHealth? = SchedulerHealth(alive = true, lastTick = "2026-10-18T10:00:00Z"),
    private val paused: Boolean = false,
    private val queueFailure: Boolean = false,
) : HealthSources {
    override fun dbReachable() = db

    override fun queueDepths(): Map<String, Long> =
        if (queueFailure) {
            error("pgmq.metrics failed")
        } else {
            mapOf(
                "runs" to 0L,
                "tasks" to 2L,
                "windows" to 0L,
                "signals" to 1L,
            )
        }

    override fun abandoned() = abandoned

    override fun served(): ServedBundle? = null

    override fun scheduler() = scheduler

    override fun refreshFailed() = refreshFailed

    override fun paused() = paused
}
