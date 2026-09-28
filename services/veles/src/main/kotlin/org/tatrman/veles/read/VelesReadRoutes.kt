// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.read

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
import org.tatrman.ttr.metadata.graph.EdgeType
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.schemaCodeToToken
import org.tatrman.ttr.metadata.query.MetadataQuery
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.search.SearchIndexHolder
import org.tatrman.ttr.metadata.search.SearchQuery
import org.tatrman.veles.grpc.PackageIndex

/**
 * Read-only JSON projection of the in-memory model for a browser catalog viewer.
 *
 * These routes mirror the gRPC read handlers in
 * [org.tatrman.veles.grpc.MetadataServiceImpl] but emit a small, stable JSON shape
 * (a TypeScript contract keys on these exact field names). Everything is derived
 * from a single [org.tatrman.ttr.metadata.registry.MetadataRegistry.read] snapshot;
 * a `null` snapshot degrades to `503 {"error":"model not loaded"}` — the same
 * fail-closed convention [org.tatrman.veles.export.metadataExportRoutes] uses.
 *
 * JSON is built with kotlinx.serialization `buildJsonObject { … }` and serialised
 * via `respondText(obj.toString(), Application.Json, status)` (the sibling export
 * file's pattern), so the routes carry no dependency on a ContentNegotiation plugin.
 *
 * NOTE ON THE SIGNATURE — the task's canonical signature is
 * `velesReadRoutes(registry)`, but `/model/search` must reuse the *same* search
 * path the gRPC `search` RPC uses (`MetadataQuery(snap, searchRegistry, indexHolder)`,
 * MetadataServiceImpl.kt:688), which needs the already-built algorithm registry
 * (incl. the "all" merge algorithm) and the per-language index holder. Those are
 * constructed once in `Application.module` (Application.kt:150-157) and are passed
 * straight through here rather than rebuilt — a search route wired to an empty
 * registry would silently return no hits.
 *
 * Packages (the index's `packages`, graph's `?package=`, every node's `pkg`) come from the same
 * [PackageIndex] the gRPC GetModel / ListObjects / ListQueries scope by (ttr-server#111): a file's
 * `package` clause, else the package its directory implies. The loader never sets
 * `QualifiedName.package`, so these routes must not read it.
 */
fun Route.velesReadRoutes(
    registry: MetadataRegistry,
    searchRegistry: SearchAlgorithmRegistry,
    searchIndexHolder: SearchIndexHolder?,
    packageIndex: PackageIndex,
) {
    // GET /model/index — packages / schemas / areas + counts + version.
    get("/model/index") {
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()

        // objectByQname() is the same all-objects view the gRPC handlers read
        // (MetadataServiceImpl.kt:251, Model.kt:39).
        val objects = snap.model.objectByQname()
        // Schema keys are "db" / "er" / "cnc" (SchemaContents.schemaCode; Model.kt:116-160,
        // the same map keyed at MetadataServiceImpl.kt:248-250).
        val schemas =
            snap.model.schemas.keys
                .sorted()
        // Distinct package of every object's source file — the packages GetModel serves
        // objects for. Objects of no package (stock roles, root-level files) are dropped.
        val packages =
            objects.values
                .mapNotNull { packageIndex.packageOf(it.sourceFile) }
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

    // GET /model/graph?schema=&package= — nodes + edges from the ModelGraph snapshot.
    get("/model/graph") {
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()

        val schemaParam = call.request.queryParameters["schema"]?.takeIf { it.isNotBlank() }
        val pkgParam = call.request.queryParameters["package"]?.takeIf { it.isNotBlank() }

        // Graph vertices are ModelObjects keyed by internalId (ModelGraph.byInternalId,
        // ModelGraph.kt:37). Filter to the requested scope; keep the surviving ids so we
        // can drop dangling edges.
        val nodes =
            snap.graph.byInternalId.values.filter { obj ->
                (schemaParam == null || schemaCodeToToken(obj.qname.schemaCode) == schemaParam) &&
                    (pkgParam == null || packageIndex.contains(pkgParam, obj.sourceFile))
            }
        val keptIds = nodes.mapTo(mutableSetOf()) { it.internalId }

        val body =
            buildJsonObject {
                putJsonArray("nodes") {
                    nodes.forEach { obj -> addJsonObject { putNodeFields(obj, packageIndex) } }
                }
                putJsonArray("edges") {
                    // forEachEdge is the graph's read-only edge walk (ModelGraph.kt:108).
                    // Edge.source / Edge.target are internalIds; resolve them back to qnames.
                    snap.graph.forEachEdge { edge ->
                        if (edge.source in keptIds && edge.target in keptIds) {
                            val from =
                                snap.graph.byInternalId[edge.source]
                                    ?.qname
                                    ?.dotted()
                            val to =
                                snap.graph.byInternalId[edge.target]
                                    ?.qname
                                    ?.dotted()
                            if (from != null && to != null) {
                                addJsonObject {
                                    put("from", from)
                                    put("to", to)
                                    put("type", edge.type.toWire())
                                }
                            }
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
        // gRPC getObject resolves a structured proto QualifiedName via
        // `objectByQname()[request.qualifiedName.toDomain()]` (MetadataServiceImpl.kt:399).
        // Over HTTP we only have the dotted string, so we match on the canonical
        // `QualifiedName.dotted()` form (QualifiedName.kt:63) — the exact string the other
        // three routes emit, so the contract round-trips regardless of the `package` field.
        val obj =
            snap.model
                .objectByQname()
                .values
                .firstOrNull { it.qname.dotted() == qnameParam }
        if (obj == null) {
            return@get call.respondText(
                """{"error":"not found"}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
        }

        // "References / dependencies" = the objects this one points at in the graph:
        // outgoing DEFINES / REFERENCES / MAPS_TO / USES edges (ModelGraph.kt:41-47,
        // edge kinds documented at ModelGraph.kt:24-32). Resolved back to dotted qnames.
        val references =
            snap.graph
                .outgoingOf(obj.internalId)
                .mapNotNull {
                    snap.graph.byInternalId[it.target]
                        ?.qname
                        ?.dotted()
                }.distinct()
                .sorted()

        val body =
            buildJsonObject {
                putJsonObject("object") { putNodeFields(obj, packageIndex) }
                // The domain ModelObject exposes only `sourceFile: String` — there is no
                // structured file/line/column (Model.kt:85). We emit the string variant of
                // the contract's `string | {file,line,column}` union.
                put("sourceLocation", obj.sourceFile)
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
}

private const val DEFAULT_SEARCH_LIMIT = 20

/** The shared node/object body: qname / kind / label / schema / pkg ("" = no package). */
private fun kotlinx.serialization.json.JsonObjectBuilder.putNodeFields(
    obj: ModelObject,
    packageIndex: PackageIndex,
) {
    put("qname", obj.qname.dotted())
    put("kind", obj.kind)
    // No uniform display "label" exists on ModelObject; the leaf identifier is what the
    // gRPC ObjectDescriptor surfaces as the display name (`local_name`,
    // MetadataServiceImpl.kt:994). Prose lives in `description` — see report note.
    put("label", obj.qname.name.substringAfterLast('.'))
    put("schema", schemaCodeToToken(obj.qname.schemaCode))
    put("pkg", packageIndex.packageOf(obj.sourceFile).orEmpty())
}

/**
 * Map the domain [EdgeType] onto the 4 contract literals. The domain enum
 * (ModelGraph.kt:224) currently has exactly these four members, so the `when` is
 * total and no "unknown → REFERENCES" fallback is reachable. If the enum grows,
 * add `else -> "REFERENCES"`.
 */
private fun EdgeType.toWire(): String =
    when (this) {
        EdgeType.DEFINES -> "DEFINES"
        EdgeType.REFERENCES -> "REFERENCES"
        EdgeType.MAPS_TO -> "MAPS_TO"
        EdgeType.USES -> "USES"
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(json: String) =
    respondText(json, ContentType.Application.Json, HttpStatusCode.OK)

private suspend fun io.ktor.server.application.ApplicationCall.respondModelNotLoaded() =
    respondText(
        """{"error":"model not loaded"}""",
        ContentType.Application.Json,
        HttpStatusCode.ServiceUnavailable,
    )
