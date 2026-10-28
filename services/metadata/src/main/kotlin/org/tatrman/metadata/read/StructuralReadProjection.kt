// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.ttr.metadata.export.DefinitionGraph
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.Er2DbRelationMapping
import org.tatrman.ttr.metadata.model.MappingSource
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.schemaCodeToToken
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.registry.RegistrySnapshot

internal data class ReadObject(
    val qname: String,
    val schema: String,
    val pkg: String,
    val sourceLocation: String,
    val body: JsonObject,
)

internal data class ReadEdge(
    val from: String,
    val to: String,
    val type: String,
)

internal data class ProjectionDiagnostics(
    val ambiguousIds: Map<String, List<String>>,
    val omittedEdges: Int,
    val collidingMdIds: Map<String, List<String>>,
) {
    val hasWarnings: Boolean get() = ambiguousIds.isNotEmpty() || collidingMdIds.isNotEmpty()
}

internal data class StructuralReadProjection(
    val objects: Map<String, ReadObject>,
    val edges: List<ReadEdge>,
    val graph: DefinitionGraph,
    val diagnostics: ProjectionDiagnostics,
)

private val mdReferences = setOf("grain", "measures", "level")
private val physicalBindings = setOf("er2db", "md2db")
private val log = LoggerFactory.getLogger(StructuralReadProjectionCache::class.java)

/**
 * HTTP DTO adaptation only; reference resolution remains library-owned. Register after
 * PackageIndex.commitOnSwap, then prime: the initial load can publish before routes are installed.
 * Old/in-flight reads build without caching, so they cannot evict the current snapshot's projection.
 */
internal class StructuralReadProjectionCache(
    private val registry: MetadataRegistry,
    private val packageIndex: PackageIndex,
) {
    @Volatile
    private var cached: Pair<RegistrySnapshot, StructuralReadProjection>? = null

    // Only versions with diagnostics are retained; requests never log or mutate this set.
    private val warnedVersions = mutableSetOf<String>()

    init {
        registry.addListener(::published)
        registry.read()?.let(::published)
    }

    @Synchronized
    private fun published(snapshot: RegistrySnapshot) {
        if (snapshot !== registry.read() || cached?.first === snapshot) return
        cached = null
        val result =
            try {
                build(snapshot)
            } catch (error: Exception) {
                log.warn("Structural read projection failed for model version {}", snapshot.model.version.value, error)
                return
            }
        if (snapshot !== registry.read()) return
        cached = snapshot to result
        val diagnostics = result.diagnostics
        if (diagnostics.hasWarnings && warnedVersions.add(snapshot.model.version.value)) {
            log.warn(
                "Structural read projection version {}: omitted {} ambiguous definition edges; " +
                    "ambiguous ids {} {}; colliding md ids {} {} (first source file wins, without priority)",
                snapshot.model.version.value,
                diagnostics.omittedEdges,
                diagnostics.ambiguousIds.size,
                diagnostics.ambiguousIds
                    .toSortedMap()
                    .entries
                    .take(5),
                diagnostics.collidingMdIds.size,
                diagnostics.collidingMdIds
                    .toSortedMap()
                    .entries
                    .take(5),
            )
        }
    }

    fun read(snapshot: RegistrySnapshot): StructuralReadProjection =
        cached?.takeIf { it.first === snapshot }?.second ?: build(snapshot)

    private fun build(snapshot: RegistrySnapshot): StructuralReadProjection {
        val typedObjects = snapshot.model.objectByQname().values
        val objects =
            typedObjects.associateTo(linkedMapOf()) { obj ->
                val qname = obj.qname.dotted()
                qname to
                    ReadObject(
                        qname,
                        schemaCodeToToken(obj.qname.schemaCode),
                        packageIndex.packageOf(obj.sourceFile).orEmpty(),
                        obj.sourceFile,
                        buildJsonObject { putNodeFields(obj, packageIndex) },
                    )
            }
        val declarations = snapshot.definitions.entries.groupBy { it.qname }
        // Match registry.swap's first-declaration selection, while retaining all declarations for diagnostics.
        val definitions = snapshot.definitions.entries.distinctBy { it.qname }
        val graph = DefinitionGraph.build(snapshot.definitions.copy(entries = definitions))
        val mdDefinitions = definitions.filter { it.model == "md" }.associateBy { it.qname }
        val mdCollisions =
            declarations
                .filterValues { entries -> entries.count { it.model == "md" } > 1 }
                .mapValues { (_, entries) -> entries.map { it.def.source.file }.distinct().sorted() }

        // Export ids classify ER objects by kind; catalog ids keep the file's namespace. Join every
        // declaration before deduplication. A partial or conflicting join must never select a survivor.
        val typedByDeclaration = typedObjects.groupBy { Triple(it.sourceFile, it.kind, it.qname.name) }
        val joins =
            declarations.mapValues { (_, entries) ->
                entries
                    .map { entry ->
                        val parentName = entry.parentQname?.let { declarations[it]?.firstOrNull()?.def?.name }
                        val name = listOfNotNull(parentName, entry.def.name).joinToString(".")
                        typedByDeclaration[
                            Triple(
                                entry.def.source.file,
                                entry.kind,
                                name,
                            ),
                        ]?.singleOrNull()?.qname?.dotted()
                    }.toSet()
            }
        val ambiguous = joins.filterValues { it.size > 1 }.keys.toMutableSet()
        val duplicateBindings =
            declarations
                .filterValues { entries ->
                    entries.count { it.kind.startsWith("er2db_") || it.kind.startsWith("md2db_") } > 1
                }.keys
        ambiguous += duplicateBindings

        fun catalogId(id: String): String? =
            when {
                id in ambiguous -> null
                else -> joins[id]?.singleOrNull() ?: id
            }

        for (node in graph.nodes.filter { it.model == "md" && !it.external }) {
            val entry = mdDefinitions.getValue(node.id)
            val body =
                buildJsonObject {
                    put("qname", node.id)
                    put("kind", node.kind)
                    put("label", entry.def.name.substringAfterLast('.'))
                    put("schema", "md")
                    put("pkg", entry.pkg)
                    entry.def.description
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("description", it) }
                    val localized = entry.def.descriptionLocalized.byLanguage
                    if (localized.isNotEmpty()) {
                        putJsonObject("descriptionLocalized") {
                            localized.toSortedMap().forEach { (k, v) ->
                                put(k, v)
                            }
                        }
                    }
                    if (!node.displayLabel.isNullOrEmpty()) {
                        putJsonObject("displayLabel") { node.displayLabel.orEmpty().forEach { (k, v) -> put(k, v) } }
                    }
                }
            objects[node.id] = ReadObject(node.id, "md", entry.pkg, entry.def.source.file, body)
        }
        // Preserve authored hierarchy order independently of the structural edge sort order.
        val levelsByHierarchy = graph.edges.filter { it.kind == "level" }.groupBy { it.from }
        for ((id, obj) in objects.toMap()) {
            if (id !in mdDefinitions || mdDefinitions.getValue(id).kind != "hierarchy") continue
            val levels =
                levelsByHierarchy[id]
                    .orEmpty()
                    .sortedBy { (it.props["order"] as? Number)?.toInt() }
                    .mapNotNull { catalogId(it.to) }
                    .filter { it in objects }
            objects[id] = obj.copy(body = JsonObject(obj.body + ("levels" to JsonArray(levels.map(::JsonPrimitive)))))
        }

        val edges = linkedSetOf<ReadEdge>()
        snapshot.graph.forEachEdge { edge ->
            val from =
                snapshot.graph.byInternalId[edge.source]
                    ?.qname
                    ?.dotted()
            val to =
                snapshot.graph.byInternalId[edge.target]
                    ?.qname
                    ?.dotted()
            if (from != null && to != null) edges += ReadEdge(from, to, edge.type.toWire())
        }
        val typedResolved = mutableSetOf<String>()
        for (mapping in snapshot.model.mappings) {
            val endpoints =
                when (mapping) {
                    is Er2DbEntityMapping ->
                        mapping.entity to
                            when (val target = mapping.target) {
                                is MappingTarget.Table -> target.qname
                                is MappingTarget.View -> target.qname
                                is MappingTarget.SqlQuery -> target.qname
                            }
                    is Er2DbAttributeMapping ->
                        (mapping.target as? AttributeMappingTarget.Column)?.let {
                            mapping.attribute to it.qname
                        }
                    is Er2DbRelationMapping -> mapping.relation to mapping.foreignKey
                    else -> null
                } ?: continue
            val from = endpoints.first.dotted()
            val to = endpoints.second.dotted()
            if (from !in objects || to !in objects) continue
            edges += ReadEdge(from, to, "MAPS_TO")
            if (mapping.mappingSource == MappingSource.Explicit) {
                typedResolved += "${mapping.qname.namespace}.${mapping.qname.name}"
            }
        }
        var omitted = 0
        for (edge in graph.edges) {
            val type =
                when {
                    edge.kind == "contains" && edge.from in mdDefinitions && edge.to in mdDefinitions -> "DEFINES"
                    edge.kind in mdReferences && edge.from in mdDefinitions -> "REFERENCES"
                    edge.kind in physicalBindings -> {
                        if (edge.kind == "er2db" && edge.id in typedResolved) continue
                        if (edge.id in duplicateBindings) {
                            omitted++
                            continue
                        }
                        "MAPS_TO"
                    }
                    else -> continue
                }
            val from = catalogId(edge.from)
            val to = catalogId(edge.to)
            if (from == null || to == null) {
                omitted++
                continue
            }
            edges += if (type == "DEFINES") ReadEdge(to, from, type) else ReadEdge(from, to, type)
        }
        return StructuralReadProjection(
            objects,
            edges.filter { it.from in objects && it.to in objects },
            graph,
            ProjectionDiagnostics(
                ambiguous.associateWith { id ->
                    declarations[id]
                        .orEmpty()
                        .map { it.def.source.file }
                        .distinct()
                        .sorted()
                },
                omitted,
                mdCollisions,
            ),
        )
    }
}
