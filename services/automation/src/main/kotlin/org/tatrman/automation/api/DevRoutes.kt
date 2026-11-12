// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.identity.caller
import org.tatrman.automation.ledger.HeldDecision
import org.tatrman.automation.writer.Held

/**
 * Amendment 2026-11-03: P2's only way to decide a held group, and dev-only after P3. Mounted only when
 * `AUTOMATION_DEV_ROUTES=true` (otherwise the path is a plain 404), inside the bearer check. `{group}` is the group
 * key's canonical JSON, URL-encoded; `role.ops` is checked against the run's world (Held). Answers `{ batch, group, applied }` or `{ batch, group, discarded }`; `409
 * superseded` when the run's fingerprints moved (D-99).
 */
fun Route.devRoutes(
    enabled: Boolean,
    held: Held,
) {
    if (!enabled) return
    post("/automation/dev/runs/{id}/held/{group}/commit") {
        val caller = call.caller()
        call.respond(held.commitHeld(call.runId(), call.group(), caller).wire("applied") { it.applied })
    }
    post("/automation/dev/runs/{id}/held/{group}/reject") {
        val caller = call.caller()
        call.respond(held.rejectHeld(call.runId(), call.group(), caller).wire("discarded") { it.discarded })
    }
}

private fun ApplicationCall.group(): JsonObject {
    val raw = parameters["group"].orEmpty()
    return runCatching {
        kotlinx.serialization.json.Json
            .parseToJsonElement(raw)
    }.getOrNull() as? JsonObject
        ?: throw AutomationException(ErrorCode.BAD_REQUEST, "the group is the key's canonical JSON object, URL-encoded")
}

private fun HeldDecision.wire(
    count: String,
    value: (HeldDecision) -> Int,
) = buildJsonObject {
    put("batch", batch)
    put("group", group)
    put(count, JsonPrimitive(value(this@wire)))
    put("status", runStatus)
}
