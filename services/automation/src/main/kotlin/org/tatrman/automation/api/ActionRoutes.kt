// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.tatrman.automation.identity.caller
import org.tatrman.automation.writer.OutcomeRequest
import org.tatrman.automation.writer.Perform
import org.tatrman.automation.writer.PerformRequest
import org.tatrman.automation.writer.Revert

/** S2.3 (§1.2 as amended 2026-11-03): `perform`, `outcome` and `revert`, inside the verified-bearer boundary. */
fun Route.actionRoutes(
    perform: Perform,
    revert: Revert,
) {
    post("/automation/runs/{id}/actions/{a}/perform") {
        val action = call.parameters["a"].orEmpty()
        call.respond(perform.perform(call.runId(), action, call.receive<PerformRequest>(), call.caller()))
    }
    post("/automation/runs/{id}/actions/{a}/outcome") {
        val action = call.parameters["a"].orEmpty()
        call.respond(perform.outcome(call.runId(), action, call.receive<OutcomeRequest>(), call.caller()))
    }
    post("/automation/runs/{id}/revert") {
        call.respond(revert.revert(call.runId(), call.caller()))
    }
}
