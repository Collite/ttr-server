// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.tatrman.automation.identity.caller
import org.tatrman.automation.protocol.ProtocolRecorder

/** Authenticated durable read; available to the initiating verified subject even without a local session. */
fun Route.protocolRoutes(protocol: ProtocolRecorder) {
    get("/automation/runs/{id}/protocol") {
        val document = protocol.read(call.runId(), call.caller())
        when (call.request.queryParameters["render"]) {
            null, "raw" -> call.respond(document)
            "pt" -> throw AutomationException(
                ErrorCode.PROTOCOL_RENDERER_UNAVAILABLE,
                "the PT reader is not configured",
            )
            else -> throw BadRequestException("render must be raw or pt")
        }
    }
}
