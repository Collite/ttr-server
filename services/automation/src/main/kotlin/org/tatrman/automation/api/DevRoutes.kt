// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.tatrman.automation.bundle.ApprovalFps
import org.tatrman.automation.bundle.Approver
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.WorldScope
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.caller
import org.tatrman.automation.ledger.HeldDecision
import org.tatrman.automation.writer.Held
import org.tatrman.automation.writer.holdsOps

/** D-184's request: one approval of a served object's current fingerprint(s). */
@Serializable
data class DevApprovalRequest(
    val objectKind: String,
    @SerialName("object") val obj: String,
    val kind: String,
    val fps: ApprovalFps,
    val note: String? = null,
)

/**
 * Amendment 2026-11-03: P2's only way to decide a held group, and dev-only after P3; with D-184, the approvals seed. Mounted only when
 * `AUTOMATION_DEV_ROUTES=true` (otherwise the path is a plain 404), inside the bearer check. `{group}` is the group
 * key's canonical JSON, URL-encoded; `role.ops` is checked against the run's world (Held). Answers `{ batch, group, applied }` or `{ batch, group, discarded }`; `409
 * superseded` when the run's fingerprints moved (D-99).
 */
fun Route.devRoutes(
    enabled: Boolean,
    held: Held,
    approver: Approver? = null,
    served: () -> Bundle? = { null },
) {
    if (!enabled) return
    // D-184 (Amendment 2026-11-12): bp-dsk's approvals seeding (`scripts/approve-all.sh`) through the same `approve()`,
    // so D-89's `409 golden-required` and the served-fp check hold. `role.ops` in every world the object is deployed
    // in (any world's, for an object no deployment uses yet). Not a v1 public surface.
    if (approver != null) {
        post("/automation/dev/approvals") {
            val caller = call.caller()
            val request = call.receive<DevApprovalRequest>()
            requireOps(caller, served(), request.objectKind, request.obj)
            val record =
                withContext(Dispatchers.IO) {
                    approver.approve(
                        request.objectKind,
                        request.obj,
                        request.fps,
                        request.kind,
                        caller.principal,
                        request.note,
                    )
                }
            call.respond(HttpStatusCode.Created, buildJsonObject { put("id", record.id) })
        }
    }
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

/** The worlds an approval of ([objectKind], [obj]) speaks for: those of the deployments that use it. */
internal fun approvalWorlds(
    bundle: Bundle,
    objectKind: String,
    obj: String,
): List<WorldScope> =
    bundle.deployments.values
        .filter { d ->
            when (objectKind) {
                "deployment" -> bundle.deployments[obj] === d
                "program" -> d.program == obj
                else -> bundle.programs[d.program]?.recipe == obj
            }
        }.mapNotNull { bundle.worldOf(it) }
        .distinctBy { it.name }

private fun requireOps(
    caller: Caller,
    bundle: Bundle?,
    objectKind: String,
    obj: String,
) {
    val worlds = bundle?.let { approvalWorlds(it, objectKind, obj) }.orEmpty()
    val allowed =
        if (worlds.isEmpty()) {
            bundle
                ?.worlds
                ?.values
                .orEmpty()
                .any { caller.holdsOps(it) }
        } else {
            worlds.all { caller.holdsOps(it) }
        }
    if (!allowed) {
        throw AutomationException(
            ErrorCode.IDENTITY,
            "${caller.principal} may not seed approvals of $objectKind $obj: it needs role.${Held.OPS} in " +
                (worlds.map { it.name }.ifEmpty { listOf("a served world") }).joinToString(", "),
            buildJsonObject { put("reason", "no-grant") },
        )
    }
}
