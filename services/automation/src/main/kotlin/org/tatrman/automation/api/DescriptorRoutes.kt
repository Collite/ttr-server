// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /automation/` (AU contracts §1.1). Until S0.2 loads a bundle, nothing is served: `served` and `latest`
 * are null and every list is empty — the descriptor of a service that has not refreshed yet. S0.2 types the
 * lists' elements and fills them; the keys are the contract's now, so a reader written against this shape keeps
 * working. [version] is the run API's.
 */
@Serializable
data class Descriptor(
    val served: ServedBundle?,
    val latest: JsonObject?,
    val previews: List<JsonObject>,
    val recipes: List<JsonObject>,
    val programs: List<JsonObject>,
    val deployments: List<JsonObject>,
    val connectors: List<JsonObject>,
    val registry: List<JsonObject>,
    val paused: Boolean,
    val version: String,
)

const val RUN_API_VERSION = "v1"

/** Authenticated: mounted under the bearer check — the caller is known to every route here. */
fun Route.descriptorRoutes(paused: () -> Boolean) {
    get("/automation/") {
        call.respond(
            Descriptor(
                served = null,
                latest = null,
                previews = emptyList(),
                recipes = emptyList(),
                programs = emptyList(),
                deployments = emptyList(),
                connectors = emptyList(),
                registry = emptyList(),
                paused = paused(),
                version = RUN_API_VERSION,
            ),
        )
    }
}
