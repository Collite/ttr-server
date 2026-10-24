// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.EvaluationPending
import org.tatrman.automation.runner.RunSessions

/** Budgeted execution; durable observation is installed separately by [runStatusRoutes]. */
fun Route.evaluateRoutes(runs: RunSessions) {
    post("/automation/runs/{id}/evaluate") {
        val id = call.runId()
        val result = runs.evaluate(id, call.caller())
        if (result != null) {
            call.respond(result)
        } else {
            val location = "/automation/runs/$id"
            call.response.header(HttpHeaders.Location, location)
            call.response.header(HttpHeaders.RetryAfter, "15")
            call.respond(HttpStatusCode.Accepted, EvaluationPending(id.toString(), location = location))
        }
    }
}
