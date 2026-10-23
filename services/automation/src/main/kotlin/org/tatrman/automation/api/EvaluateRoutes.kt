// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.RunSessions

/** Synchronous evaluation; deliberately unmounted until production runtime and async budgets are wired. */
fun Route.evaluateRoutes(runs: RunSessions) {
    post("/automation/runs/{id}/evaluate") {
        val result = runs.read(call.runId(), call.caller(), { Unit }) { runner, _ -> runner.evaluate() }
        call.respond(result)
    }
}
