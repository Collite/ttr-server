// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.RunProgress
import java.util.UUID

/** Observation only: no session registry, current bundle, execution owner or dispatcher is required. */
fun Route.runStatusRoutes(readStatus: suspend (UUID, Caller) -> RunProgress) {
    get("/automation/runs/{id}") {
        val progress = readStatus(call.runId(), call.caller())
        if (progress.status in setOf("open", "running")) call.response.header(HttpHeaders.RetryAfter, "15")
        call.respond(progress)
    }
}
