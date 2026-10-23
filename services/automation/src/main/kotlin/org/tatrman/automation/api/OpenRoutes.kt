// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.tatrman.automation.identity.caller
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.runner.OpenResult
import org.tatrman.automation.runner.RunSessions
import org.tatrman.automation.runner.OpenRequest

/** The registry owns the atomic claim/registration handoff before an HTTP response may escape. */
fun Route.openRoutes(runs: RunSessions) = openRoutes(runs::open)

/** Policy transport seam for specs; production uses the lifecycle overload above. */
internal fun Route.openRoutes(open: suspend (String, OpenRequest, Caller) -> OpenResult) {
    post("/automation/recipes/{r}/runs") {
        val request = call.receive<OpenRequest>()
        val result = open(call.parameters["r"].orEmpty(), request, call.caller())
        result.response.location?.let { call.response.header(HttpHeaders.Location, it) }
        if (result.httpStatus == 202) call.response.header(HttpHeaders.RetryAfter, "15")
        call.respond(HttpStatusCode.fromValue(result.httpStatus), result.response)
    }
}
