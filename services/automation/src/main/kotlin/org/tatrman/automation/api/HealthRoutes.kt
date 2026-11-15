// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ServedBundle(
    val bundle: String,
    val commit: String,
    val modelFp: String?,
    val refreshedAt: String,
)

/**
 * `GET /automation/health` (AU contracts §1.1) — D-72's hardcoded set. D-142 (S3.2) removed `scheduler` and `queues`:
 * the service has neither.
 */
@Serializable
data class HealthReport(
    val status: String,
    val served: ServedBundle?,
    val abandoned: Long?,
    val refreshFailed: Boolean,
    val paused: Boolean,
)

/** What the health report reads. */
interface HealthSources {
    fun dbReachable(): Boolean

    fun abandoned(): Long

    fun served(): ServedBundle?

    fun refreshFailed(): Boolean

    fun paused(): Boolean
}

private val log = LoggerFactory.getLogger("org.tatrman.automation.api.HealthRoutes")

/**
 * The status rule: `DOWN` (503) when the ledger cannot be read; `DEGRADED` when the last /refresh failed;
 * `UP` otherwise. The abandoned count informs and never degrades — the ledger keeps
 * runs forever (D-73), so one old abandonment would hold the status down for good; alert recipes judge it.
 */
fun healthReport(sources: HealthSources): HealthReport {
    if (!sources.dbReachable()) {
        return HealthReport(
            status = "DOWN",
            served = sources.served(),
            abandoned = null,
            refreshFailed = sources.refreshFailed(),
            paused = sources.paused(),
        )
    }
    val refreshFailed = sources.refreshFailed()
    return HealthReport(
        status = if (refreshFailed) "DEGRADED" else "UP",
        served = sources.served(),
        abandoned = sources.abandoned(),
        refreshFailed = refreshFailed,
        paused = sources.paused(),
    )
}

@Serializable
data class ProbeStatus(
    val status: String,
)

/** `/health` (liveness: the process answers), `/ready` (the ledger answers) and `/automation/health`. */
fun Route.healthRoutes(sources: HealthSources) {
    get("/health") { call.respond(ProbeStatus("UP")) }
    get("/ready") {
        val ready = runCatching { sources.dbReachable() }.getOrDefault(false)
        if (ready) {
            call.respond(
                ProbeStatus("UP"),
            )
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, ProbeStatus("DOWN"))
        }
    }
    get("/automation/health") {
        val report =
            try {
                healthReport(sources)
            } catch (e: Exception) {
                log.warn("health: a source failed; answering DOWN", e)
                HealthReport("DOWN", null, null, refreshFailed = false, paused = false)
            }
        call.respond(if (report.status == "DOWN") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, report)
    }
}
