// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.tatrman.automation.identity.caller
import org.tatrman.automation.protocol.Digests
import org.tatrman.automation.writer.TypedPerform
import org.tatrman.automation.writer.TypedPerformRequest
import java.util.UUID

/** S3.1 T6: the digest (D-148) and the typed-action perform (D-168). Install inside bearer authentication. */
fun Route.engineSeamRoutes(
    digests: Digests,
    perform: TypedPerform,
) {
    get("/automation/runs/{id}/digest") {
        val id =
            runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                ?: throw AutomationException(ErrorCode.BAD_REQUEST, "the run id is not a UUID")
        val p = call.request.queryParameters
        call.respond(digests.digest(id, call.caller(), p["group"], p["action"] ?: "notify", p["lang"] ?: "en"))
    }

    post("/automation/deployments/{d}/actions/{a}/perform") {
        val request = call.receive<TypedPerformRequest>()
        call.respond(
            perform.perform(call.parameters["d"].orEmpty(), call.parameters["a"].orEmpty(), request, call.caller()),
        )
    }
}
