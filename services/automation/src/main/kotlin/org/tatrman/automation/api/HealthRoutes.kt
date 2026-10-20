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

@Serializable
data class SchedulerHealth(
    val alive: Boolean,
    val lastTick: String?,
)

/** `GET /automation/health` (AU contracts §1.1) — D-72's hardcoded set. */
@Serializable
data class HealthReport(
    val status: String,
    val served: ServedBundle?,
    val scheduler: SchedulerHealth,
    val queues: Map<String, Long>,
    val abandoned: Long?,
    val refreshFailed: Boolean,
    val paused: Boolean,
)

/**
 * What the health report reads. [scheduler] answers null while the build has no scheduler (it lands at
 * S3.2): the report then shows it not alive without degrading the status, since nothing is down.
 */
interface HealthSources {
    fun dbReachable(): Boolean

    fun queueDepths(): Map<String, Long>

    fun abandoned(): Long

    fun served(): ServedBundle?

    fun scheduler(): SchedulerHealth?

    fun refreshFailed(): Boolean

    fun paused(): Boolean
}

private val log = LoggerFactory.getLogger("org.tatrman.automation.api.HealthRoutes")

/**
 * The status rule: `DOWN` (503) when the ledger cannot be read; `DEGRADED` when the last /refresh failed or
 * the scheduler stopped; `UP` otherwise. The abandoned count informs and never degrades — the ledger keeps
 * runs forever (D-73), so one old abandonment would hold the status down for good; alert recipes judge it.
 */
fun healthReport(sources: HealthSources): HealthReport {
    val scheduler = sources.scheduler()
    if (!sources.dbReachable()) {
        return HealthReport(
            status = "DOWN",
            served = sources.served(),
            scheduler = scheduler ?: NO_SCHEDULER,
            queues = emptyMap(),
            abandoned = null,
            refreshFailed = sources.refreshFailed(),
            paused = sources.paused(),
        )
    }
    val refreshFailed = sources.refreshFailed()
    val degraded = refreshFailed || scheduler?.alive == false
    return HealthReport(
        status = if (degraded) "DEGRADED" else "UP",
        served = sources.served(),
        scheduler = scheduler ?: NO_SCHEDULER,
        queues = sources.queueDepths(),
        abandoned = sources.abandoned(),
        refreshFailed = refreshFailed,
        paused = sources.paused(),
    )
}

private val NO_SCHEDULER = SchedulerHealth(alive = false, lastTick = null)

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
                HealthReport("DOWN", null, NO_SCHEDULER, emptyMap(), null, refreshFailed = false, paused = false)
            }
        call.respond(if (report.status == "DOWN") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, report)
    }
}
