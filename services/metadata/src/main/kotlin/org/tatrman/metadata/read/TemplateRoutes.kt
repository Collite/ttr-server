// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.ttr.metadata.registry.MetadataRegistry

/** #272: read-only, cluster-internal routes, with the same availability/error convention as layouts. */
fun Route.templateRoutes(registry: MetadataRegistry) {
    get("/model/reports") {
        val snapshot = registry.read() ?: return@get call.respondModelNotLoaded()
        val reports =
            snapshot
                .templateContent()
                .reportsById.values
                .sortedBy {
                    it.summary
                        .getValue("id")
                        .jsonPrimitive.content
                }
        call.respondJson(buildJsonObject { put("reports", JsonArray(reports.map { it.summary })) }.toString())
    }
    get("/model/report") {
        val snapshot = registry.read() ?: return@get call.respondModelNotLoaded()
        val id = call.request.queryParameters["id"]
        if (id.isNullOrBlank()) return@get call.respondError(HttpStatusCode.BadRequest, "missing id")
        val report =
            snapshot.templateContent().reportsById[id]
                ?: return@get call.respondError(HttpStatusCode.NotFound, "not found")
        call.respondJson(
            buildJsonObject {
                put("id", id)
                put("yaml", report.yaml)
                put("template", report.template)
                put("sha256", report.sha256)
            }.toString(),
        )
    }
    get("/model/forms") {
        val snapshot = registry.read() ?: return@get call.respondModelNotLoaded()
        val forms =
            snapshot
                .templateContent()
                .formsByTarget.values
                .sortedBy { it.getValue("target").jsonPrimitive.content }
        call.respondJson(buildJsonObject { put("forms", JsonArray(forms.toList())) }.toString())
    }
}
