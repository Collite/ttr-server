// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.RunCloser

/** Install inside the same verified-bearer boundary as every run route. */
fun Route.closeRoutes(closer: RunCloser) {
    post("/automation/runs/{id}/close") {
        call.respond(closer.close(call.runId(), call.caller()))
    }
}
