// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.read

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.ttr.metadata.layout.ResolvedLayout
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.parser.model.LayoutDocument

/**
 * VL-P2 — the layouts committed next to the model (VL contracts §6, V-39): `GET /model/layouts` lists them,
 * `GET /model/layout?name=` serves one, resolved against the model it was published with. Both read the
 * snapshot's `LayoutIndex`, built from the same loads as the model and swapped in with it (V-37); a layout
 * file that failed is not here but on `/status.issues[]` (V-38). Read-only: git is the write path (G-4).
 *
 * Every field of both bodies is always present — an empty list as `[]`, empty positions as `{}`, no skin /
 * positionsFrom as `null` (A-VL-2): the viewer reads the body as given, and its fixtures pin the key set.
 */
fun Route.layoutRoutes(registry: MetadataRegistry) {
    get("/model/layouts") {
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()
        val body =
            LayoutsBody(
                layouts = snap.layouts.all.map { it.summary() },
                modelVersion = snap.model.version.value,
            )
        call.respondJson(LAYOUT_JSON.encodeToString(LayoutsBody.serializer(), body))
    }

    get("/model/layout") {
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()
        val name = call.request.queryParameters["name"].orEmpty()
        if (!LayoutDocument.IDENTIFIER.matches(name)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "bad name")
        }
        val layout = snap.layouts[name] ?: return@get call.respondError(HttpStatusCode.NotFound, "not found")
        call.respondJson(LAYOUT_JSON.encodeToString(LayoutBody.serializer(), layout.body(snap.model.version.value)))
    }
}

/** V-39 / A-VL-2: defaults and nulls are written, never left out. */
private val LAYOUT_JSON =
    Json {
        encodeDefaults = true
        explicitNulls = true
    }

@Serializable
private data class LayoutsBody(
    val layouts: List<LayoutSummary>,
    val modelVersion: String,
)

@Serializable
private data class LayoutSummary(
    val name: String,
    val schema: String,
    val objectCount: Int,
    val missingCount: Int,
    val ambiguousCount: Int,
    val hasPositions: Boolean,
    val source: String,
)

@Serializable
private data class LayoutBody(
    val name: String,
    val schema: String,
    val objects: List<String>,
    val positions: Map<String, Point>,
    val mode: String,
    val skin: String?,
    val collapsed: List<String>,
    val missing: List<String>,
    val ambiguous: List<Ambiguity>,
    val ignored: List<String>,
    val positionsFrom: String?,
    val source: String,
    val modelVersion: String,
)

/** Integral on the wire (V-3, V-5): a served document is canonical, its coordinates already rounded. */
@Serializable
private data class Point(
    val x: Long,
    val y: Long,
)

@Serializable
private data class Ambiguity(
    val ref: String,
    val candidates: List<String>,
)

private fun ResolvedLayout.summary() =
    LayoutSummary(
        name = name,
        schema = schema,
        objectCount = objects.size,
        missingCount = missing.size,
        ambiguousCount = ambiguous.size,
        hasPositions = positions.isNotEmpty(),
        source = source,
    )

private fun ResolvedLayout.body(modelVersion: String) =
    LayoutBody(
        name = name,
        schema = schema,
        objects = objects,
        positions = positions.mapValues { (_, p) -> Point(Math.round(p.x), Math.round(p.y)) },
        mode = mode,
        skin = skin,
        collapsed = collapsed,
        missing = missing,
        ambiguous = ambiguous.map { Ambiguity(it.ref, it.candidates) },
        ignored = ignored,
        positionsFrom = positionsFrom,
        source = source,
        modelVersion = modelVersion,
    )

private suspend fun ApplicationCall.respondJson(json: String) = respondText(json, ContentType.Application.Json, HttpStatusCode.OK)

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    error: String,
) = respondText("""{"error":"$error"}""", ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondModelNotLoaded() = respondError(HttpStatusCode.ServiceUnavailable, "model not loaded")
