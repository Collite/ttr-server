// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.metadata.scope.TagScope
import org.tatrman.ttr.metadata.layout.ResolvedLayout
import org.tatrman.ttr.metadata.layout.closureLayout
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.parser.model.LayoutDocument

/**
 * VL-P2 — the layouts committed next to the model (VL contracts §6, V-39): `GET /model/layouts` lists them,
 * `GET /model/layout?name=` serves one, resolved against the model it was published with. Both read the
 * snapshot's `LayoutIndex`, built from the same loads as the model and swapped in with it (V-37); a layout
 * file that failed is not here but on `/status.issues[]` (V-38). Read-only: git is the write path (G-4).
 *
 * MW S4.3b / D-MW-108: `GET /model/layout?tags=a,b[&schema=er|db]` projects the ResolveTags closure
 * from one captured snapshot. ER is the default; schema is part of the normalized tag-set cache key.
 *
 * Every field of both bodies is always present — an empty list as `[]`, empty positions as `{}`, no skin /
 * positionsFrom as `null` (A-VL-2): the viewer reads the body as given, and its fixtures pin the key set. The
 * error bodies are the other read routes' (`respondError`, `respondModelNotLoaded` in MetadataReadRoutes.kt).
 */
fun Route.layoutRoutes(registry: MetadataRegistry) {
    val tagLayouts = TagLayoutCache(registry)
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
        val params = call.request.queryParameters
        if (params.contains("name") && params.contains("tags")) {
            return@get call.respondError(HttpStatusCode.BadRequest, "name and tags are mutually exclusive")
        }
        val schema = params["schema"] ?: "er"
        if (schema !in setOf("er", "db")) {
            return@get call.respondError(HttpStatusCode.BadRequest, "layout schema must be er or db")
        }
        if (params.contains("tags")) {
            val tags =
                try {
                    TagScope.normalize(params.getAll("tags").orEmpty().flatMap { it.split(',') })
                } catch (e: IllegalArgumentException) {
                    return@get call.respondError(HttpStatusCode.BadRequest, "tags must contain nonblank values")
                }
            val representation = tagLayouts.get(snap, tags, schema)
            call.response.header(HttpHeaders.ETag, representation.etag)
            return@get call.respondJson(representation.body)
        }
        val name = params["name"].orEmpty()
        if (!LayoutDocument.IDENTIFIER.matches(name)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "bad name")
        }
        val layout = snap.layouts[name] ?: return@get call.respondError(HttpStatusCode.NotFound, "not found")
        call.respondJson(LAYOUT_JSON.encodeToString(LayoutBody.serializer(), layout.body(snap.model.version.value)))
    }
}

/** One bounded cache for the current snapshot; old in-flight reads never evict the current snapshot's entries. */
private class TagLayoutCache(
    private val registry: MetadataRegistry,
) {
    private data class Key(
        val tags: Set<String>,
        val schema: String,
    )

    data class Representation(
        val body: String,
        val etag: String,
    )

    private var snapshot: RegistrySnapshot? = null
    private val entries = linkedMapOf<Key, Representation>()

    init {
        registry.addListener {
            synchronized(this) {
                snapshot = null
                entries.clear()
            }
        }
    }

    @Synchronized
    fun get(
        captured: RegistrySnapshot,
        tags: Set<String>,
        schema: String,
    ): Representation {
        fun render(): Representation {
            val scope = TagScope.resolve(captured, tags)
            val layout = closureLayout(captured, scope.objects, schema)
            val body = LAYOUT_JSON.encodeToString(LayoutBody.serializer(), layout.body(captured.model.version.value))
            val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            return Representation(body, "\"${HexFormat.of().formatHex(digest)}\"")
        }
        if (captured !== registry.read()) return render()
        if (snapshot !== captured) {
            snapshot = captured
            entries.clear()
        }
        val key = Key(tags.toSet(), schema)
        entries[key]?.let { return it }
        val rendered = render()
        // Unknown tag combinations need not accumulate for the whole lifetime of a stable model.
        if (entries.size >= 128) entries.remove(entries.keys.first())
        entries[key] = rendered
        return rendered
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
