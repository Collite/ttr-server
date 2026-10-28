// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.ttr.metadata.export.DefinitionGraph
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbRelationMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.schemaCodeToToken

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

internal data class StructuralReadProjection(
    val objects: Map<String, ReadObject>,
    val edges: List<ReadEdge>,
)

/**
 * HTTP DTO adaptation only. The library's typed ModelGraph predates md; its DefinitionGraph already
 * resolves md containment, grain, measures, hierarchy levels and physical bindings. Do not duplicate
 * that resolution or add md semantics here. Both inputs belong to the captured registry snapshot.
 * One definition graph is retained, so requests never rebuild it at a stable snapshot.
 */
internal class StructuralReadProjectionCache(
    private val packageIndex: PackageIndex,
) {
    @Volatile
    private var cached: Pair<RegistrySnapshot, DefinitionGraph>? = null

    private fun definitionGraph(snapshot: RegistrySnapshot): DefinitionGraph {
        cached?.takeIf { it.first === snapshot }?.let { return it.second }
        return synchronized(this) {
            cached?.takeIf { it.first === snapshot }?.second ?: DefinitionGraph
                .build(
                    snapshot.definitions.copy(entries = snapshot.definitions.entries.distinctBy { it.qname }),
                ).also { cached = snapshot to it }
        }
    }

    fun read(snapshot: RegistrySnapshot): StructuralReadProjection {
        val typedObjects = snapshot.model.objectByQname().values
        val objects =
            typedObjects.associateTo(linkedMapOf()) { obj ->
                val qname = obj.qname.dotted()
                val body = buildJsonObject { putNodeFields(obj, packageIndex) }
                qname to
                    ReadObject(
                        qname,
                        schemaCodeToToken(obj.qname.schemaCode),
                        packageIndex.packageOf(obj.sourceFile).orEmpty(),
                        obj.sourceFile,
                        body,
                    )
            }
        // The definition graph resolves aliases and package-qualified refs in the library.
        val graph = definitionGraph(snapshot)
        val definitions = snapshot.definitions.entries.distinctBy { it.qname }
        val mdDefinitions = definitions.filter { it.model == "md" }.associateBy { it.qname }
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
                }
            objects[node.id] = ReadObject(node.id, "md", entry.pkg, entry.file, body)
        }
        // Export ids classify ER objects by kind; the older catalog ids use the file's namespace
        // (a relation may be er.entity.R, not er.relation.R). Join by source provenance and declared
        // name to preserve catalog ids. Reference resolution itself remains library-owned.
        val definitionsById = definitions.associateBy { it.qname }
        val typedByDeclaration = typedObjects.groupBy { Triple(it.sourceFile, it.kind, it.qname.name) }
        val catalogIds =
            definitions
                .mapNotNull { entry ->
                    val name =
                        entry.parentQname?.let { definitionsById[it]?.def?.name + "." + entry.def.name }
                            ?: entry.def.name
                    typedByDeclaration[Triple(entry.def.source.file, entry.kind, name)]?.singleOrNull()?.let {
                        entry.qname to it.qname.dotted()
                    }
                }.toMap()

        fun catalogId(id: String) = catalogIds[id] ?: id

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
        // Inline er2db mappings are materialized by the library in the typed model, rather than
        // separate definition entries. Project the same logical-to-physical edge for both forms.
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
                }
            endpoints?.let { (from, to) -> edges += ReadEdge(from.dotted(), to.dotted(), "MAPS_TO") }
        }
        for (edge in graph.edges) {
            when {
                edge.kind == "contains" && edge.from in mdDefinitions && edge.to in mdDefinitions ->
                    edges += ReadEdge(catalogId(edge.to), catalogId(edge.from), "DEFINES")
                edge.kind in setOf("grain", "measures", "level") && edge.from in mdDefinitions ->
                    edges += ReadEdge(catalogId(edge.from), catalogId(edge.to), "REFERENCES")
                edge.kind in setOf("er2db", "md2db") ->
                    edges +=
                        ReadEdge(catalogId(edge.from), catalogId(edge.to), "MAPS_TO")
            }
        }
        // Unresolved/external export stubs are not catalog objects; never expose dangling edges.
        return StructuralReadProjection(objects, edges.filter { it.from in objects && it.to in objects })
    }
}
