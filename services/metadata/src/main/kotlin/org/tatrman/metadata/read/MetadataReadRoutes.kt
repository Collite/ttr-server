// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import org.tatrman.ttr.metadata.graph.EdgeType
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.DbForeignKey
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.schemaCodeToToken
import org.tatrman.ttr.metadata.query.MetadataQuery
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.search.SearchIndexHolder
import org.tatrman.ttr.metadata.search.SearchQuery
import org.tatrman.metadata.grpc.PackageIndex

/**
 * Read-only JSON projection of the in-memory model for a browser catalog viewer.
 *
 * These routes mirror the gRPC read handlers in
 * [org.tatrman.metadata.grpc.MetadataServiceImpl] but emit a small, stable JSON shape
 * (a TypeScript contract keys on these exact field names). Everything is derived
 * from a single [org.tatrman.ttr.metadata.registry.MetadataRegistry.read] snapshot;
 * a `null` snapshot degrades to `503 {"error":"model not loaded"}` — the same
 * fail-closed convention [org.tatrman.metadata.export.metadataExportRoutes] uses.
 *
 * JSON is built with kotlinx.serialization `buildJsonObject { … }` and serialised
 * via `respondText(obj.toString(), Application.Json, status)` (the sibling export
 * file's pattern), so the routes carry no dependency on a ContentNegotiation plugin.
 *
 * NOTE ON THE SIGNATURE — the task's canonical signature is
 * `metadataReadRoutes(registry)`, but `/model/search` must reuse the *same* search
 * path the gRPC `search` RPC uses (`MetadataQuery(snap, searchRegistry, indexHolder)`,
 * MetadataServiceImpl.kt:688), which needs the already-built algorithm registry
 * (incl. the "all" merge algorithm) and the per-language index holder. Those are
 * constructed once in `Application.module` (Application.kt:150-157) and are passed
 * straight through here rather than rebuilt — a search route wired to an empty
 * registry would silently return no hits.
 *
 * Typed objects use the same [PackageIndex] as the gRPC reads (ttr-server#111); md objects use
 * the captured definition's package provenance. Both follow the loader's declared package clause,
 * else the directory-implied package. Never read `QualifiedName.package` (the loader leaves it empty).
 */
fun Route.metadataReadRoutes(
    registry: MetadataRegistry,
    searchRegistry: SearchAlgorithmRegistry,
    searchIndexHolder: SearchIndexHolder?,
    packageIndex: PackageIndex,
    lexiconSourceIds: Set<String>,
) {
    val projection = StructuralReadProjectionCache(packageIndex)

    // GET /model/index — packages / schemas / areas + counts + version.
    get("/model/index") {
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()

        // Keep index, graph and detail on one catalog view, including the library's md definitions.
        val objects = projection.read(snap).objects
        val schemas =
            (snap.model.schemas.keys + objects.values.map { it.schema }.filter { it.isNotBlank() })
                .sorted()
        // Distinct package of every object's source file — the packages GetModel serves
        // objects for. Objects of no package (stock roles, root-level files) are dropped.
        val packages =
            objects.values
                .map { it.pkg }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        // Subject areas, keyed by bare name (Model.areas / AreaRecord, Model.kt:33-62;
        // surfaced by the ResolveArea RPC at MetadataServiceImpl.kt:915). Empty map → [].
        val areas =
            snap.model.areas.keys
                .sorted()

        val body =
            buildJsonObject {
                putJsonArray("packages") { packages.forEach { add(it) } }
                putJsonArray("schemas") { schemas.forEach { add(it) } }
                putJsonArray("areas") { areas.forEach { add(it) } }
                putJsonObject("counts") {
                    put("objects", objects.size)
                    put("schemas", schemas.size)
                    put("areas", areas.size)
                }
                put("modelVersion", snap.model.version.value)
            }
        call.respondJson(body.toString())
    }

    // GET /model/graph?schema=&package= — nodes + edges from the captured structural projection.
    get("/model/graph") {
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()

        val schemaParam = call.request.queryParameters["schema"]?.takeIf { it.isNotBlank() }
        val pkgParam = call.request.queryParameters["package"]?.takeIf { it.isNotBlank() }

        val catalog = projection.read(snap)
        val nodes =
            catalog.objects.values.filter { obj ->
                (schemaParam == null || obj.schema == schemaParam) &&
                    (pkgParam == null || obj.pkg == pkgParam)
            }
        val keptQnames = nodes.mapTo(mutableSetOf()) { it.qname }
        val body =
            buildJsonObject {
                putJsonArray("nodes") { nodes.forEach { add(it.body) } }
                putJsonArray("edges") {
                    catalog.edges.filter { it.from in keptQnames && it.to in keptQnames }.forEach { edge ->
                        addJsonObject {
                            put("from", edge.from)
                            put("to", edge.to)
                            put("type", edge.type)
                        }
                    }
                }
            }
        call.respondJson(body.toString())
    }

    // GET /model/object?qname=... — one object + its source location + outgoing references.
    get("/model/object") {
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()

        val qnameParam = call.request.queryParameters["qname"].orEmpty()
        val catalog = projection.read(snap)
        val obj = catalog.objects[qnameParam]
        if (obj == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "not found")
        }

        val references =
            catalog.edges
                .filter { it.from == obj.qname }
                .map { it.to }
                .distinct()
                .sorted()

        val body =
            buildJsonObject {
                put("object", obj.body)
                // The domain ModelObject exposes only `sourceFile: String` — there is no
                // structured file/line/column (Model.kt:85). We emit the string variant of
                // the contract's `string | {file,line,column}` union.
                put("sourceLocation", obj.sourceLocation)
                putJsonArray("references") { references.forEach { add(it) } }
            }
        call.respondJson(body.toString())
    }

    // GET /model/search?query=...&limit=... — reuses the gRPC search path verbatim.
    get("/model/search") {
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()

        val queryText = call.request.queryParameters["query"].orEmpty()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_SEARCH_LIMIT
        if (queryText.isBlank()) {
            return@get call.respondJson("""{"hits":[]}""")
        }

        // Identical to MetadataServiceImpl.search (MetadataServiceImpl.kt:676-688):
        // SearchQuery defaults algorithm="all" and language="cs" (SearchQuery.kt:11-24,
        // matching DEFAULT_SEARCH_ALGORITHM); `limit` maps to page_size. MetadataQuery
        // drives algorithm selection + per-language index + post-processing.
        val hits =
            MetadataQuery(snap, searchRegistry, searchIndexHolder)
                .search(SearchQuery(query = queryText, limit = limit))

        val body =
            buildJsonObject {
                putJsonArray("hits") {
                    hits.forEach { hit ->
                        addJsonObject {
                            put("qname", hit.ownerQname.dotted())
                            put("score", hit.score)
                            put("matchedField", hit.matchedField)
                        }
                    }
                }
            }
        call.respondJson(body.toString())
    }

    // VL-P2 — GET /model/layouts, GET /model/layout?name= (LayoutRoutes.kt).
    layoutRoutes(registry)
    templateRoutes(registry)
    lexiconRoutes(registry, lexiconSourceIds)
}

private const val DEFAULT_SEARCH_LIMIT = 20

/**
 * The shared node/object body: qname / kind / label / schema / pkg ("" = no package), then the
 * optional fields below — each OMITTED when empty, so the shape only ever grows (ttr-server#185):
 *
 *  - `description` (plain form) and `descriptionLocalized` (`{lang: text}`, the NLS-P10 form), on any
 *    object. At most one of the two is authored.
 *  - `displayLabel` (`{lang: text}`) on entities and attributes; `labelPlural` (string) on entities.
 *  - on a relation: `from` / `to` (entity qnames) and `cardinality` (`{from: "0..*", to: "1"}`, the
 *    authored range form) — what a catalog ER view needs to draw the relation as an edge.
 *  - on a foreign key: `fromColumns` / `toColumns` (column qnames).
 *
 * Every language is sent; the reader picks one. The catalog viewer chooses its language at runtime
 * (`?lang=`, a setting, the browser) and applies the same D7 chain to descriptions that the gRPC
 * `GetModel(locale)` applies here (requested locale → plain form → `en` → first by language code).
 */
internal fun JsonObjectBuilder.putNodeFields(
    obj: ModelObject,
    packageIndex: PackageIndex,
) {
    put("qname", obj.qname.dotted())
    put("kind", obj.kind)
    // The leaf identifier, as the gRPC ObjectDescriptor surfaces it (`local_name`,
    // MetadataServiceImpl.kt:994). The localized name is `displayLabel` below.
    put("label", obj.qname.name.substringAfterLast('.'))
    put("schema", schemaCodeToToken(obj.qname.schemaCode))
    put("pkg", packageIndex.packageOf(obj.sourceFile).orEmpty())

    if (obj.description.isNotBlank()) put("description", obj.description)
    putLocalized("descriptionLocalized", obj.descriptionLocalized)
    when (obj) {
        is Entity -> {
            putLocalized("displayLabel", obj.displayLabel)
            if (obj.labelPlural.isNotBlank()) put("labelPlural", obj.labelPlural)
        }
        is Attribute -> putLocalized("displayLabel", obj.displayLabel)
        is Relation -> {
            put("from", obj.fromEntity.dotted())
            put("to", obj.toEntity.dotted())
            putJsonObject("cardinality") {
                put("from", rangeOf(obj.cardinality.fromMin, obj.cardinality.fromMax))
                put("to", rangeOf(obj.cardinality.toMin, obj.cardinality.toMax))
            }
        }
        is DbForeignKey -> {
            putJsonArray("fromColumns") { obj.fromColumns.forEach { add(it.dotted()) } }
            putJsonArray("toColumns") { obj.toColumns.forEach { add(it.dotted()) } }
        }
        else -> Unit
    }
}

/** `{lang: text}`, languages in a stable order; nothing at all for an empty text. */
private fun JsonObjectBuilder.putLocalized(
    key: String,
    text: LocalizedText,
) {
    if (text.isEmpty) return
    putJsonObject(key) { text.byLanguage.toSortedMap().forEach { (lang, value) -> put(lang, value) } }
}

/** A cardinality bound pair in the authored TTR form: `1`, `0..1`, `0..*` (`-1` = unbounded). */
private fun rangeOf(
    min: Int,
    max: Int,
): String =
    when {
        max == -1 -> "$min..*"
        min == max -> "$min"
        else -> "$min..$max"
    }

/**
 * Map the domain [EdgeType] onto the 4 contract literals. The domain enum
 * (ModelGraph.kt:224) currently has exactly these four members, so the `when` is
 * total and no "unknown → REFERENCES" fallback is reachable. If the enum grows,
 * add `else -> "REFERENCES"`.
 */
internal fun EdgeType.toWire(): String =
    when (this) {
        EdgeType.DEFINES -> "DEFINES"
        EdgeType.REFERENCES -> "REFERENCES"
        EdgeType.MAPS_TO -> "MAPS_TO"
        EdgeType.USES -> "USES"
    }

// The response helpers every read route shares (the layout routes too), so the error bodies keep one shape.

internal suspend fun io.ktor.server.application.ApplicationCall.respondJson(json: String) =
    respondText(json, ContentType.Application.Json, HttpStatusCode.OK)

/** `{"error":"<error>"}` with [status]. */
internal suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    error: String,
) = respondText(buildJsonObject { put("error", error) }.toString(), ContentType.Application.Json, status)

internal suspend fun io.ktor.server.application.ApplicationCall.respondModelNotLoaded() =
    respondError(HttpStatusCode.ServiceUnavailable, "model not loaded")
