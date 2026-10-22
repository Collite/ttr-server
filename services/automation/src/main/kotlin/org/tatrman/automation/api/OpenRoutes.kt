// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.Open
import org.tatrman.automation.runner.OpenRequest
import org.tatrman.automation.runner.RunContext

/**
 * Mounted under bearer authentication. [opened] hands the new pinned context to the run lifecycle exactly once.
 * Application installs this with that lifecycle in S1.1's production-integration task; Open alone runs no islands.
 */
fun Route.openRoutes(
    open: Open,
    opened: suspend (RunContext) -> Unit,
) {
    post("/automation/recipes/{r}/runs") {
        val request = call.receive<OpenRequest>()
        val caller = call.caller()
        val result = withContext(Dispatchers.IO) { open.open(call.parameters["r"].orEmpty(), request, caller) }
        result.context?.let { opened(it) }
        result.response.location?.let { call.response.header(HttpHeaders.Location, it) }
        call.respond(HttpStatusCode.fromValue(result.httpStatus), result.response)
    }
}
