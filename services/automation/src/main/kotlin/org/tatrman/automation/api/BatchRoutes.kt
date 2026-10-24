// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.RunStatusReader

/** Authenticated durable preview; no evaluation is started by a batch read. */
fun Route.batchRoutes(status: RunStatusReader) {
    get("/automation/runs/{id}/batch") { call.respond(status.batch(call.runId(), call.caller())) }
}
