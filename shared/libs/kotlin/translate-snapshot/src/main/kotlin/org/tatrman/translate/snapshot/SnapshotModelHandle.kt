// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectEntry
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
) : ModelHandle {
    override fun tables(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelTable> =
        tablesByQname.filterKeys {
            it.schemaCode == schemaCode && it.namespace.equals(namespace, ignoreCase = true)
        }

    override fun columns(tableQname: QualifiedName): List<ModelColumn> =
        tablesByQname[tableQname]?.columns ?: emptyList()

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
            SchemaCode.DB -> tablesByQname.keys.mapTo(mutableSetOf()) { it.namespace }
            SchemaCode.ER -> entitiesByQname.keys.mapTo(mutableSetOf()) { it.namespace }
            SchemaCode.OBJ -> savedQueriesByQname.keys.mapTo(mutableSetOf()) { it.namespace }
            else -> emptySet()
        }

    companion object {
        fun from(snapshot: ModelSnapshot): SnapshotModelHandle {
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
                                surfaceType = SurfaceType.fromTag(entry.attribute.type) ?: SurfaceType.TEXT,
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
                    ObjectEntry.ContentCase.ER2DB_ENTITY_MAPPING -> {
                        val mapping =
                            when (entry.er2DbEntityMapping.targetCase) {
                                org.tatrman.meta.v1.Er2DbEntityMappingDetail.TargetCase.TABLE ->
                                    EntityMapping.ToTable(
                                        entry.er2DbEntityMapping.table,
                                        if (entry.er2DbEntityMapping.hasWhereFilter()) {
                                            entry.er2DbEntityMapping.whereFilter
                                        } else {
                                            null
                                        },
                                    )
                                org.tatrman.meta.v1.Er2DbEntityMappingDetail.TargetCase.VIEW ->
                                    EntityMapping.ToTable(
                                        entry.er2DbEntityMapping.view,
                                        if (entry.er2DbEntityMapping.hasWhereFilter()) {
                                            entry.er2DbEntityMapping.whereFilter
                                        } else {
                                            null
                                        },
                                    )
                                org.tatrman.meta.v1.Er2DbEntityMappingDetail.TargetCase.SQL_QUERY ->
                                    EntityMapping.ToQuery(entry.er2DbEntityMapping.sqlQuery)
                                else -> null
                            }
                        if (mapping != null) entityMappings[entry.er2DbEntityMapping.entity] = mapping
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
                                            SurfaceType.fromTag(it.type) ?: SurfaceType.TEXT,
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
            // GH #113 — an attribute mapped to something other than a column (an expression; Veles
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
            )
        }

        private fun DbColumnSummary.toModelColumn(): ModelColumn =
            ModelColumn(name = name, surfaceType = surfaceTypeOf(dataType), nullable = nullable)
    }
}

/**
 * Best-effort mapping from a raw SQL `data_type` string to a DSL [SurfaceType]. Mirrors the
 * in-process mapping in Veles (the metadata/model-graph service, `MetadataModelHandle`); kept
 * local rather than shared to avoid a Translate → Veles dependency.
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
