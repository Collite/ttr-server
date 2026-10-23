// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.tatrman.automation.identity.caller
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.runner.EvaluationPending
import org.tatrman.automation.runner.RunProgress
import org.tatrman.automation.runner.RunSessions
import java.util.UUID

/** Budgeted execution plus observation-only status; runtime supplies its durable, owner-scoped reader. */
fun Route.evaluateRoutes(
    runs: RunSessions,
    readStatus: suspend (UUID, Caller) -> RunProgress,
) {
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
    get("/automation/runs/{id}") {
        val progress = readStatus(call.runId(), call.caller())
        if (progress.status in setOf("open", "running")) call.response.header(HttpHeaders.RetryAfter, "15")
        call.respond(progress)
    }
}
