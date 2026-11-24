// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import org.tatrman.meta.v1.Er2DbEntityMappingDetail.TargetCase
import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelTable

import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ParamSpec
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType

/**
 * A [ModelHandle] built from a metadata-service [ModelSnapshot]. Exposes db tables/views
 * as [ModelTable]s and db foreign keys; ER tables aren't exposed (the v1 translator pipeline
 * targets `target_schema = DB`). Snapshot-stable: constructed per snapshot fetch and held
 * unchanged for the lifetime of any compilation that uses it.
 *
 * Core "Placement" (D-199/D-200): an unbound placed entity owns a table named after the entity,
 * with bare attribute columns and its key attributes as the primary key. Package and named-entity
 * allowlists are opt-in; explicit bindings always win. These tables have no physical namespace:
 * the connection's search_path selects it, so lookup accepts any DB namespace.
 * Real tables, including those without a primary key, take precedence over placed tables.
 * A placed name exists in both lanes, so SQL over it must state `source_schema` (the Door sends ER).
 */
class SnapshotModelHandle(
    private val tablesByQname: Map<QualifiedName, ModelTable>,
    private val foreignKeys: List<ModelForeignKey>,
    private val version: String,
    private val entitiesByQname: Map<QualifiedName, ModelEntity> = emptyMap(),
    private val relationsList: List<ModelRelation> = emptyList(),
    private val entityMappings: Map<QualifiedName, EntityMapping> = emptyMap(),
    private val savedQueriesByQname: Map<QualifiedName, ModelSavedQuery> = emptyMap(),
    private val savedQueryBodies: Map<QualifiedName, SavedQueryBody> = emptyMap(),
    private val attributeRenamesByEntity: Map<QualifiedName, Map<String, String>> = emptyMap(),
    private val placedTables: Map<String, ModelTable> = emptyMap(),
) : ModelHandle {
    override fun tables(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelTable> {
        val real =
            tablesByQname.filterKeys {
                it.schemaCode == schemaCode && it.namespace.equals(namespace, ignoreCase = true)
            }
        val placed =
            if (schemaCode == SchemaCode.DB) {
                placedTables.values
                    .filterNot { table -> real.keys.any { it.name.equals(table.qname.name, ignoreCase = true) } }
                    .associate { table ->
                        val qn =
                            table.qname
                                .toBuilder()
                                .setNamespace(namespace)
                                .build()
                        qn to table.copy(qname = qn)
                    }
            } else {
                emptyMap()
            }
        return placed + real
    }

    override fun columns(tableQname: QualifiedName): List<ModelColumn> =
        tablesByQname[tableQname]?.columns
            ?: placedTables[tableQname.name]?.takeIf { tableQname.schemaCode == SchemaCode.DB }?.columns
            ?: emptyList()

    override fun foreignKeys(): List<ModelForeignKey> = foreignKeys

    override fun entities(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelEntity> =
        entitiesByQname.filterKeys {
            it.schemaCode == schemaCode && it.namespace.equals(namespace, ignoreCase = true)
        }

    override fun attributes(entityQname: QualifiedName): List<ModelAttribute> =
        entitiesByQname[entityQname]?.attributes ?: emptyList()

    override fun relations(): List<ModelRelation> = relationsList

    override fun entityMapping(entityQname: QualifiedName): EntityMapping? = entityMappings[entityQname]

    override fun attributeColumnRenames(entityQname: QualifiedName): Map<String, String> =
        attributeRenamesByEntity[entityQname] ?: emptyMap()

    override fun savedQueries(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelSavedQuery> =
        savedQueriesByQname.filterKeys {
            it.schemaCode == schemaCode && it.namespace.equals(namespace, ignoreCase = true)
        }

    override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
        savedQueryBodies[queryQname] ?: error("No saved query body for $queryQname")

    override fun currentVersion(): String = version

    override fun namespaces(schemaCode: SchemaCode): Set<String> =
        when (schemaCode) {
            SchemaCode.DB ->
                tablesByQname.keys.mapTo(mutableSetOf()) { it.namespace }.apply {
                    if (placedTables.isNotEmpty()) add("")
                }
            SchemaCode.ER -> entitiesByQname.keys.mapTo(mutableSetOf()) { it.namespace }
            SchemaCode.OBJ -> savedQueriesByQname.keys.mapTo(mutableSetOf()) { it.namespace }
            else -> emptySet()
        }

    companion object {
        /**
         * [savedQueriesAsObj] (LQ, contracts L-20/L-21) — also serve every **parsed** saved query to the SQL surface
         * under its obj name, `obj.<namespace>.<name>` (`er.query.x` and `query.x` → `obj.query.x`, `db.<schema>.x` →
         * `obj.<schema>.x`), with its stored plan as the body, so a saved query that reads another one parses. Two
         * saved queries with one obj name are served under neither. The metadata worker turns this on for each wave of
         * its dependency-ordered parse; the translate service turns it on at run time (L-28), where a pattern, the
         * lattice or free SQL sends a saved query's text, and that text may read another saved query. The snapshot
         * carries a stored plan only for a PARSED query, so a FAILED or PENDING one is served under no name.
         */
        fun from(
            snapshot: ModelSnapshot,
            savedQueriesAsObj: Boolean = false,
            placedPackages: Set<String> = emptySet(),
            placedEntities: Set<String> = emptySet(),
        ): SnapshotModelHandle {
            val tables = LinkedHashMap<QualifiedName, ModelTable>()
            val fks = mutableListOf<ModelForeignKey>()
            val entitiesMap = LinkedHashMap<QualifiedName, ModelEntity>()
            val attributesMap = mutableMapOf<QualifiedName, MutableList<Pair<QualifiedName, ModelAttribute>>>()
            val noColumnAttributes = mutableSetOf<QualifiedName>()
            val relations = mutableListOf<ModelRelation>()
            val entityMappings = mutableMapOf<QualifiedName, EntityMapping>()
            val savedQueriesMap = LinkedHashMap<QualifiedName, ModelSavedQuery>()
            val savedQueryBodies = mutableMapOf<QualifiedName, SavedQueryBody>()
            val attributeRenames = mutableMapOf<QualifiedName, MutableMap<String, String>>()
            val entriesByKind = snapshot.objectsList.groupBy { it.contentCase }

            fun names(kind: ObjectEntry.ContentCase) =
                BindingNames(
                    entriesByKind[kind].orEmpty().map {
                        it.objectDescriptor.qualifiedName to it.objectDescriptor.packageName
                    },
                )

            val entityNames = names(ObjectEntry.ContentCase.ENTITY)
            val tableNames = names(ObjectEntry.ContentCase.TABLE)
            val viewNames = names(ObjectEntry.ContentCase.VIEW)
            val queryNames = names(ObjectEntry.ContentCase.QUERY)
            val bindings =
                entriesByKind[ObjectEntry.ContentCase.ER2DB_ENTITY_MAPPING]
                    .orEmpty()
                    .map { it.er2DbEntityMapping }
                    .mapNotNull { binding -> entityNames.resolve(binding.entity)?.let { it to binding } }
                    .groupBy({ it.first }, { it.second })

            for ((entity, candidates) in bindings) {
                val exact = candidates.filter { it.entity == entity }
                for (binding in exact.ifEmpty { candidates }) {
                    val filter = binding.whereFilter.takeIf { binding.hasWhereFilter() }
                    val mapping =
                        when (binding.targetCase) {
                            TargetCase.TABLE ->
                                tableNames
                                    .resolve(binding.table, keepMissingBare = true)
                                    ?.let { EntityMapping.ToTable(it, filter) }
                            TargetCase.VIEW ->
                                viewNames
                                    .resolve(binding.view, keepMissingBare = true)
                                    ?.let { EntityMapping.ToTable(it, filter) }
                            TargetCase.SQL_QUERY ->
                                queryNames
                                    .resolve(binding.sqlQuery, keepMissingBare = true)
                                    ?.let { EntityMapping.ToQuery(it) }
                            else -> null
                        }
                    if (mapping != null) entityMappings[entity] = mapping
                }
            }
            for (entry in snapshot.objectsList) {
                val qn = entry.objectDescriptor.qualifiedName
                when (entry.contentCase) {
                    ObjectEntry.ContentCase.TABLE ->
                        tables[qn] =
                            ModelTable(
                                qn,
                                entry.table.columnsList.map { it.toModelColumn() },
                                entry.table.primaryKeyList,
                            )
                    ObjectEntry.ContentCase.VIEW ->
                        tables[qn] = ModelTable(qn, entry.view.columnsList.map { it.toModelColumn() })
                    ObjectEntry.ContentCase.FOREIGN_KEY ->
                        fks +=
                            ModelForeignKey(
                                from = entry.foreignKey.fromColumnsList,
                                to = entry.foreignKey.toColumnsList,
                            )
                    ObjectEntry.ContentCase.ENTITY ->
                        entitiesMap[qn] = ModelEntity(qn, emptyList())
                    ObjectEntry.ContentCase.ATTRIBUTE -> {
                        val entityQn = entry.attribute.entity
                        // The qname.name (and therefore localName) is entity-prefixed by the
                        // YAML importer for cross-entity uniqueness, e.g. `produkt.název_produktu`.
                        // Strip the prefix so Calcite sees the bare attribute name as a column.
                        val attr =
                            ModelAttribute(
                                name = entry.objectDescriptor.localName.substringAfterLast('.'),
                                // The model's own type names (`decimal`, `date`, `integer`, …), not
                                // only the five DSL tags: an exact-tag lookup sent `decimal` to TEXT,
                                // and SUM over a TEXT attribute was implicitly CAST to DECIMAL(19, 9),
                                // which overflows from 10^10 up once a CASE/COALESCE re-casts the sum.
                                surfaceType = surfaceTypeOf(entry.attribute.type),
                                nullable = entry.attribute.nullable,
                                isKey = entry.attribute.isKey,
                            )
                        attributesMap.getOrPut(entityQn) { mutableListOf() }.add(qn to attr)
                    }
                    ObjectEntry.ContentCase.RELATION -> {
                        val pairs = entry.relation.joinPairsList.map { it.fromAttr to it.toAttr }
                        relations +=
                            ModelRelation(
                                fromEntity = entry.relation.fromEntity,
                                toEntity = entry.relation.toEntity,
                                joinPairs = pairs,
                            )
                    }
                    ObjectEntry.ContentCase.QUERY -> {
                        savedQueriesMap[qn] = ModelSavedQuery(qn)
                        savedQueryBodies[qn] =
                            SavedQueryBody(
                                planNode = entry.query.canonicalForm,
                                parameters =
                                    entry.query.parametersList.map {
                                        ParamSpec(
                                            it.name,
                                            surfaceTypeOf(it.type),
                                        )
                                    },
                                outputColumns = emptyList(),
                            )
                    }
                    ObjectEntry.ContentCase.ER2DB_ATTRIBUTE_MAPPING -> {
                        // YamlImportSource emits one mapping per ER attribute (e.g. attribute
                        // qname `er.entity.produkt.id_produktu`). Derive the entity qname from
                        // the attribute's prefix; strip the prefix to get the bare attr name.
                        // Skip expression-target mappings (denormalised display) — deferred.
                        val attrQn = entry.er2DbAttributeMapping.attribute
                        val targetCase = entry.er2DbAttributeMapping.targetCase
                        if (targetCase != org.tatrman.meta.v1.Er2DbAttributeMappingDetail.TargetCase.COLUMN) {
                            noColumnAttributes += attrQn
                        }
                        if (targetCase == org.tatrman.meta.v1.Er2DbAttributeMappingDetail.TargetCase.COLUMN) {
                            val entityName = attrQn.name.substringBeforeLast('.', missingDelimiterValue = "")
                            if (entityName.isNotEmpty()) {
                                val entityQn =
                                    QualifiedName
                                        .newBuilder()
                                        .setSchemaCode(attrQn.schemaCode)
                                        .setNamespace(attrQn.namespace)
                                        .setName(entityName)
                                        .build()
                                val bareAttrName = attrQn.name.substringAfterLast('.')
                                val columnName =
                                    entry.er2DbAttributeMapping.column.name
                                        .substringAfterLast('.')
                                // Only record entries where the names actually differ — keeps
                                // the rename sweep cheap and preserves v1 behaviour for matched
                                // names.
                                if (bareAttrName != columnName) {
                                    attributeRenames
                                        .getOrPut(entityQn) { mutableMapOf() }[bareAttrName] = columnName
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            // GH #113 — an attribute mapped to something other than a column (an expression; Metadata
            // sends those with the target unset, as the raw text has no plan.v1 form yet) has no
            // physical column behind it, and nothing here can compute it. Left in the ER catalog, it
            // rode along in every scan of its entity, so MAP_TO_PHYSICAL asked the table for a column
            // that does not exist and EVERY query over the entity failed — even one never naming it.
            // Left out, the rest of the entity is queryable and a query naming it fails as an
            // unknown column. (Projecting the expression over the physical row needs translator
            // support: `ModelHandle` has no way to carry it.)
            val finalEntities =
                entitiesMap.mapValues { (qn, entity) ->
                    entity.copy(
                        attributes =
                            attributesMap[qn]
                                .orEmpty()
                                .filterNot { (attrQn, _) -> attrQn in noColumnAttributes }
                                .map { it.second },
                    )
                }
            val placedTables = LinkedHashMap<String, ModelTable>()
            val placedCandidates =
                snapshot.objectsList.filter { entry ->
                    val descriptor = entry.objectDescriptor
                    val qn = descriptor.qualifiedName
                    entry.contentCase == ObjectEntry.ContentCase.ENTITY &&
                        qn.schemaCode == SchemaCode.ER &&
                        qn !in bindings &&
                        (descriptor.packageName in placedPackages || qn.name in placedEntities)
                }
            // Placement fixes the table name to the entity name: never guess between two owners.
            val ambiguousNames =
                placedCandidates
                    .groupBy { it.objectDescriptor.qualifiedName.name }
                    .filterValues { entries -> entries.map { it.objectDescriptor.qualifiedName }.distinct().size > 1 }
                    .keys
            for (entry in placedCandidates) {
                val qn = entry.objectDescriptor.qualifiedName
                if (qn.name in ambiguousNames) continue
                val attrs = finalEntities.getValue(qn).attributes
                val tableQn =
                    qn
                        .toBuilder()
                        .setSchemaCode(SchemaCode.DB)
                        .setNamespace("")
                        .build()
                placedTables[qn.name] =
                    ModelTable(
                        tableQn,
                        attrs.map { ModelColumn(it.name, it.surfaceType, it.nullable) },
                        attrs.filter { it.isKey }.map { it.name },
                    )
                entityMappings[qn] = EntityMapping.ToTable(tableQn)
                attributeRenames.remove(qn)
            }
            if (savedQueriesAsObj) addObjAliases(snapshot, savedQueriesMap, savedQueryBodies)
            return SnapshotModelHandle(
                tables,
                fks,
                version = snapshot.model.version,
                finalEntities,
                relations,
                entityMappings,
                savedQueriesMap,
                savedQueryBodies,
                attributeRenames,
                placedTables,
            )
        }

        /** The obj name a saved query is read under (L-20). */
        fun objName(query: QualifiedName): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.OBJ)
                .setNamespace(query.namespace)
                .setName(query.name)
                .build()

        /**
         * Every parsed saved query (a stored plan) under its obj name, when no other saved query shares that name; the
         * worker reports a reference to a shared name as ambiguous before it parses (L-20).
         */
        private fun addObjAliases(
            snapshot: ModelSnapshot,
            savedQueries: MutableMap<QualifiedName, ModelSavedQuery>,
            bodies: MutableMap<QualifiedName, SavedQueryBody>,
        ) {
            val queries = snapshot.objectsList.filter { it.contentCase == ObjectEntry.ContentCase.QUERY }
            val byObjName =
                queries.groupBy {
                    objName(it.objectDescriptor.qualifiedName).let { o ->
                        "${o.namespace}.${o.name}".lowercase()
                    }
                }
            for (shared in byObjName.values) {
                val entry = shared.singleOrNull() ?: continue
                if (entry.query.canonicalForm.nodeCase == PlanNode.NodeCase.NODE_NOT_SET) continue
                val qn = entry.objectDescriptor.qualifiedName
                val alias = objName(qn)
                savedQueries[alias] = ModelSavedQuery(alias)
                bodies[alias] = bodies.getValue(qn)
            }
        }

        private fun DbColumnSummary.toModelColumn(): ModelColumn =
            ModelColumn(name = name, surfaceType = surfaceTypeOf(dataType), nullable = nullable)
    }
}

/**
 * Best-effort mapping from a raw SQL `data_type` string to a DSL [SurfaceType]. Mirrors the
 * in-process mapping in Metadata (the metadata/model-graph service, `MetadataModelHandle`); kept
 * local rather than shared to avoid a Translate → Metadata dependency.
 */
internal fun surfaceTypeOf(dataType: String): SurfaceType {
    val base =
        dataType
            .trim()
            .substringBefore('(')
            .substringBefore(' ')
            .lowercase()
    return when (base) {
        in INT_TYPES -> SurfaceType.INT
        in FLOAT_TYPES -> SurfaceType.FLOAT
        in BOOL_TYPES -> SurfaceType.BOOL
        in DATETIME_TYPES -> SurfaceType.DATETIME
        in TEXT_TYPES -> SurfaceType.TEXT
        else -> SurfaceType.TEXT
    }
}

private val INT_TYPES = setOf("int", "integer", "bigint", "smallint", "tinyint", "long", "int4", "int8")
private val TEXT_TYPES =
    setOf("text", "varchar", "nvarchar", "char", "nchar", "string", "clob", "uniqueidentifier", "ntext")
private val FLOAT_TYPES = setOf("decimal", "numeric", "float", "double", "real", "money", "smallmoney", "number")
private val BOOL_TYPES = setOf("bool", "boolean", "bit")
private val DATETIME_TYPES =
    setOf("date", "time", "datetime", "datetime2", "timestamp", "smalldatetime", "datetimeoffset")
