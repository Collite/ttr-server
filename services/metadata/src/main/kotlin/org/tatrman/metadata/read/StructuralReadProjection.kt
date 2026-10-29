// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.ttr.metadata.export.DefinedObject
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
import org.tatrman.ttr.writer.TtrRenderer
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.CubeletMeasure
import org.tatrman.ttr.parser.model.Definition
import java.util.IdentityHashMap

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

internal data class MdCollision(
    val kept: String,
    val dropped: List<String>,
)

internal data class ProjectionDiagnostics(
    val ambiguousIds: Map<String, List<String>>,
    val omittedEdges: Int,
    val collidingMdIds: Map<String, MdCollision>,
) {
    val hasWarnings: Boolean get() = ambiguousIds.isNotEmpty() || collidingMdIds.isNotEmpty()
}

internal data class StructuralReadProjection(
    val objects: Map<String, ReadObject>,
    val edges: List<ReadEdge>,
    val diagnostics: ProjectionDiagnostics,
) {
    val outgoingByFrom = edges.groupBy { it.from }
}

private val mdReferences = setOf("grain", "measures", "level")
private val physicalBindings = setOf("er2db", "md2db")
private val log = LoggerFactory.getLogger(StructuralReadProjectionCache::class.java)

// Render only duplicate groups. Source positions distinguish declarations, not their semantics.
private fun List<DefinedObject>.identicalCopies(): Boolean =
    map { listOf(TtrRenderer.renderDef(it.def), it.imports, it.fileModel, it.fileNamespace, it.parentQname) }
        .distinct()
        .size == 1

private fun DefinedObject.diagnosticLocation(): String =
    "${def.source.file}:${def.source.line} parent=${parentQname ?: "<root>"}"

private data class BindingCandidate(
    val key: String?,
    val edge: ReadEdge,
)

/**
 * HTTP DTO adaptation only; reference resolution remains library-owned. Register after
 * PackageIndex.commitOnSwap. Register and prime under the registry monitor: this depends on
 * MetadataRegistry.swap being @Synchronized through all listeners, including package publication.
 * Old/in-flight reads build without caching, so they cannot evict the current snapshot's projection.
 */
internal class StructuralReadProjectionCache(
    private val registry: MetadataRegistry,
    private val packageIndex: PackageIndex,
) {
    @Volatile
    private var cached: Pair<RegistrySnapshot, StructuralReadProjection>? = null

    @Volatile
    private var lastWarnedVersion: String? = null

    init {
        synchronized(registry) {
            registry.addListener(::published)
            registry.read()?.let(::published)
        }
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
        if (diagnostics.hasWarnings && lastWarnedVersion != snapshot.model.version.value) {
            lastWarnedVersion = snapshot.model.version.value
            log.warn(
                "Structural read projection version {}: omitted {} binding/definition edges; " +
                    "ambiguous ids {} {}; colliding md ids {} {}",
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
        // 0.18.12 lifts inline measures with a null parentQname. Recover diagnostic context
        // from their existing AST membership, without changing export ids or resolving references.
        val inlineParents = IdentityHashMap<Definition, String>()
        for (entry in snapshot.definitions.entries) {
            val cubelet = entry.def as? CubeletDef ?: continue
            cubelet.measures.filterIsInstance<CubeletMeasure.Inline>().forEach {
                inlineParents[it.measure] = entry.qname
            }
        }
        val mdCollisions =
            declarations
                .filterValues { entries -> entries.count { it.model == "md" } > 1 }
                .mapValues { (_, entries) ->
                    entries.map { it.copy(parentQname = it.parentQname ?: inlineParents[it.def]) }
                }.filterValues { entries -> !entries.identicalCopies() }
                .mapValues { (_, entries) ->
                    MdCollision(
                        entries.first().diagnosticLocation(),
                        entries.drop(1).map { it.diagnosticLocation() },
                    )
                }

        // Export ids classify ER objects by kind; catalog ids keep the file's namespace. Join every
        // declaration before deduplication. A precedence copy may join only the winning source,
        // but declarations with different model/namespace provenance remain ambiguous.
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
        val ambiguous =
            joins
                .filter { (id, matches) ->
                    val joined = matches.filterNotNull().toSet()
                    joined.size >= 2 ||
                        (
                            joined.size == 1 &&
                                declarations.getValue(id).let { entries ->
                                    entries.mapNotNull { it.fileModel }.distinct().size > 1 ||
                                        entries.mapNotNull { it.fileNamespace }.distinct().size > 1
                                }
                        )
                }.keys
                .toMutableSet()

        val duplicateBindings =
            declarations
                .filterValues { entries ->
                    entries.count { it.kind.startsWith("er2db_") || it.kind.startsWith("md2db_") } > 1 &&
                        !entries.identicalCopies()
                }.keys
        ambiguous += duplicateBindings

        fun catalogId(id: String): String? =
            when {
                id in ambiguous -> null
                else -> joins[id]?.filterNotNull()?.singleOrNull() ?: id
            }

        val mdNodes = graph.nodes.filter { it.model == "md" && !it.external }
        val mdNodeIds = mdNodes.mapNotNull { catalogId(it.id) }.toSet()
        val levelsByHierarchy = graph.edges.filter { it.kind == "level" }.groupBy { it.from }
        for (node in mdNodes) {
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
                    if (node.kind == "hierarchy") {
                        val levels =
                            levelsByHierarchy[node.id]
                                .orEmpty()
                                .sortedBy { (it.props["order"] as? Number)?.toInt() }
                                .mapNotNull { catalogId(it.to) }
                                .filter { it in mdNodeIds }
                        if (levels.isNotEmpty()) putJsonArray("levels") { levels.forEach { add(it) } }
                    }
                    if (!node.displayLabel.isNullOrEmpty()) {
                        putJsonObject("displayLabel") { node.displayLabel.orEmpty().forEach { (k, v) -> put(k, v) } }
                    }
                }
            objects[node.id] = ReadObject(node.id, "md", entry.pkg, entry.def.source.file, body)
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
        val candidates = linkedSetOf<BindingCandidate>()
        var omitted = 0
        // Only projected physical families participate. Inline and explicit copies share a key.
        val typedBindings =
            snapshot.model.mappings
                .mapNotNull { mapping ->
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
                        } ?: return@mapNotNull null
                    Triple(
                        "${mapping.qname.namespace}.${mapping.qname.name}",
                        ReadEdge(endpoints.first.dotted(), endpoints.second.dotted(), "MAPS_TO"),
                        mapping.mappingSource,
                    )
                }.groupBy { it.first }
        val conflictingTyped = mutableSetOf<String>()
        for ((key, bindings) in typedBindings) {
            val pairs = bindings.map { it.second }.toSet()
            if (pairs.size > 1) {
                conflictingTyped += key
                ambiguous += key
                omitted += pairs.size
                continue
            }
            val edge = pairs.single()
            if (edge.from !in objects || edge.to !in objects) continue
            candidates += BindingCandidate(key, edge)
            if (bindings.any { it.third == MappingSource.Explicit }) typedResolved += key
        }
        for (edge in graph.edges) {
            val type =
                when {
                    edge.kind == "contains" && edge.from in mdDefinitions && edge.to in mdDefinitions -> "DEFINES"
                    edge.kind in mdReferences && edge.from in mdDefinitions -> "REFERENCES"
                    edge.kind in physicalBindings -> {
                        if (edge.id in conflictingTyped) continue // Already counted each distinct pair.
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
            val projected = if (type == "DEFINES") ReadEdge(to, from, type) else ReadEdge(from, to, type)
            if (type == "MAPS_TO") {
                if (from in objects && to in objects) candidates += BindingCandidate(edge.id, projected)
            } else {
                edges += projected
            }
        }
        // The query path has one physical target per logical object, across all binding keys/paths.
        for ((_, bindings) in candidates.groupBy { it.edge.from }) {
            val distinctEdges = bindings.map { it.edge }.toSet()
            if (distinctEdges.map { it.to }.distinct().size > 1) {
                omitted += distinctEdges.size
                ambiguous += bindings.mapNotNull { it.key }
            } else {
                edges += distinctEdges
            }
        }
        return StructuralReadProjection(
            objects,
            edges.filter { it.from in objects && it.to in objects },
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
