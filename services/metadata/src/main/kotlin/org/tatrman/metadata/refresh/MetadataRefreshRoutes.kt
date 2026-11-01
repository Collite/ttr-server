// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Unauthenticated synchronous refresh (DF decision); HTTP and RPC share captured outcomes. */
internal fun Route.metadataRefreshRoutes(refresher: GuardedRefresher) {
    post("/refresh") {
        val outcome =
            refresher.refreshOutcome(
                call.request.queryParameters["source_id"].orEmpty(),
                true,
                call.request.queryParameters["branch"].orEmpty(),
            )
        val results = outcome.results
        val snap = outcome.snapshot
        val body =
            buildJsonObject {
                putOps(outcome)
                put("status", outcome.status)
                putJsonArray("errors") {
                    outcome.lastAttempt
                        ?.errors
                        .orEmpty()
                        .forEach { add(it) }
                }
                putJsonArray("warnings") {
                    outcome.lastAttempt
                        ?.warnings
                        .orEmpty()
                        .forEach { add(it) }
                }
                put("model_files", outcome.lastAttempt?.modelFiles ?: 0)
                putJsonArray("ignored_files") {
                    outcome.lastAttempt
                        ?.ignoredFiles
                        .orEmpty()
                        .forEach { add(it) }
                }
                put("model_version", JsonPrimitive(snap?.model?.version?.value ?: ""))
                put("swapped", JsonPrimitive(results.any { it.snapshotSwapped }))
                putJsonArray("sources") {
                    results.forEach { r ->
                        add(
                            buildJsonObject {
                                put("source_id", JsonPrimitive(r.sourceId))
                                put("success", JsonPrimitive(r.success))
                                if (r.errorMessage.isNotEmpty()) {
                                    put("error", JsonPrimitive(r.errorMessage))
                                }
                            },
                        )
                    }
                }
            }
        call.respond(HttpStatusCode.fromValue(outcome.httpStatus), body)
    }
}
