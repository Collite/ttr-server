// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.api

import org.tatrman.health.content.ContentService
import org.tatrman.health.service.HealthCheckResult
import org.tatrman.health.service.HealthCheckService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Application.healthRoutes(
    service: HealthCheckService,
    content: ContentService,
) {
    routing {
        // Self-liveness/readiness: is THIS aggregator process up? Answers immediately, independent
        // of any downstream target — the pod probe target. Contrast /health/all, which fans out to
        // every configured target (each up to a 30s timeout) and is the DASHBOARD roll-up, not a
        // self-probe: using it as a probe lets one unreachable target's latency kill the pod.
        get("/healthz") {
            call.respond(HttpStatusCode.OK, buildJsonObject { put("status", JsonPrimitive("ok")) })
        }

        get("/health/{technology}") {
            val technology =
                call.parameters["technology"]?.lowercase()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "Technology is required")

            try {
                val result = service.checkHealth(technology)
                respondHealthResult(call, result)
            } catch (e: IllegalArgumentException) {
                call.respondError(
                    HttpStatusCode.NotFound,
                    "Unknown technology: $technology",
                    service.getSupportedTechnologies(),
                )
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, e.message ?: "Internal error")
            }
        }

        get("/health/{technology}/detailed") {
            val technology =
                call.parameters["technology"]?.lowercase()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "Technology is required")

            try {
                val result = service.checkHealthDetailed(technology)
                respondHealthResult(call, result)
            } catch (e: IllegalArgumentException) {
                call.respondError(
                    HttpStatusCode.NotFound,
                    "Unknown technology: $technology",
                    service.getSupportedTechnologies(),
                )
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, e.message ?: "Internal error")
            }
        }

        get("/health/all") {
            val thresholdStr = call.request.queryParameters["threshold"]
            val threshold = thresholdStr?.toIntOrNull() ?: 100

            val result = service.checkAllHealth(threshold)
            val httpStatus = if (result.status == "healthy") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(httpStatus, result)
        }

        // The landing reads this: every technology's health and, where configured, its content.
        get("/health/all/detailed") {
            val result =
                coroutineScope {
                    val health = async { service.checkAllHealth(100) }
                    val status = if (content.configured) async { content.all() } else null
                    val snapshot = health.await()
                    val statusAll = status?.await() ?: return@coroutineScope snapshot
                    snapshot.copy(
                        technologies = snapshot.technologies.map { it.copy(content = statusAll.technologies[it.name]) },
                        content = statusAll.summary,
                    )
                }
            call.respond(result)
        }

        // Only the content: every technology with a `status` block (cached, see ContentService). Not to
        // be confused with `/status`, the open-tier status page's JSON twin (StatusRoutes).
        get("/status/all") {
            call.respond(content.all())
        }
    }
}

private suspend fun respondHealthResult(
    call: RoutingCall,
    result: HealthCheckResult,
) {
    if (result.status == "healthy") {
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("status", JsonPrimitive("healthy"))
                put("technology", JsonPrimitive(result.technology))
                put("timestamp", JsonPrimitive(result.timestamp))
                put("source", JsonPrimitive(result.source))
            },
        )
    } else {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            buildJsonObject {
                put("status", JsonPrimitive("unhealthy"))
                put("technology", JsonPrimitive(result.technology))
                put("error", JsonPrimitive(result.error ?: "Unknown error"))
                put("timestamp", JsonPrimitive(result.timestamp))
                put("source", JsonPrimitive(result.source))
            },
        )
    }
}

private suspend fun RoutingCall.respondError(
    status: HttpStatusCode,
    message: String,
    supported: List<String>? = null,
) {
    respond(
        status,
        buildJsonObject {
            put("error", JsonPrimitive(message))
            if (supported != null) {
                put(
                    "supported",
                    buildJsonArray {
                        supported.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        },
    )
}
