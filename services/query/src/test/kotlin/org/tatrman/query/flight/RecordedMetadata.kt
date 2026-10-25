// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.DbViewDetail
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ListObjectsResponse
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleAttribute
import org.tatrman.meta.v1.ModelBundleEntity
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ModelBundleTable
import org.tatrman.meta.v1.ModelBundleView
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.PageInfo
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Metadata answering from the hartland catalog BI-0 recorded (`flight/hartland-catalog.txt`): the four `ListObjects`
 * and the one `GetModel` the edge's catalog makes, with call counters. [version] is the model version it serves.
 * `GetModel` answers the objects of the packages asked for.
 */
class RecordedMetadata(
    var version: String = "01HARTLAND",
    private val model: ModelBundle = hartlandModel(),
    /** Page size for `ListObjects`, to exercise paging. */
    private val pageSize: Int = 20,
) : MetadataCatalogClient {
    val versionCalls = AtomicInteger()
    val listCalls = AtomicInteger()
    val modelCalls = AtomicInteger()
    val requestedPackages = mutableListOf<List<String>>()

    override suspend fun modelVersion(): String {
        versionCalls.incrementAndGet()
        return version
    }

    override suspend fun listObjects(request: ListObjectsRequest): ListObjectsResponse {
        listCalls.incrementAndGet()
        val all =
            when {
                request.schema == SchemaCode.ER && request.kind == "entity" ->
                    model.entitiesList.map {
                        it.objectDescriptor
                    }
                request.schema == SchemaCode.DB && request.kind == "table" ->
                    model.tablesList.map {
                        it.objectDescriptor
                    }
                request.schema == SchemaCode.ER && request.kind == "query" ->
                    (model.namedQueriesList + model.patternQueriesList).map { it.objectDescriptor }
                request.schema == SchemaCode.DB && request.kind == "view" -> model.viewsList.map { it.objectDescriptor }
                else -> emptyList()
            }
        val from = request.page.pageToken.toIntOrNull() ?: 0
        val page = all.drop(from).take(pageSize)
        val next = if (from + pageSize < all.size) (from + pageSize).toString() else ""
        return ListObjectsResponse
            .newBuilder()
            .addAllItems(page)
            .setPageInfo(PageInfo.newBuilder().setNextPageToken(next).setTotalCount(all.size))
            .build()
    }

    override suspend fun getModel(request: GetModelRequest): GetModelResponse {
        modelCalls.incrementAndGet()
        requestedPackages += request.packagesList
        val asked = request.packagesList.toSet()

        fun ObjectDescriptor.asked() = packageName in asked
        val scoped =
            model
                .toBuilder()
                .clearEntities()
                .addAllEntities(model.entitiesList.filter { it.objectDescriptor.asked() })
                .clearNamedQueries()
                .addAllNamedQueries(model.namedQueriesList.filter { it.objectDescriptor.asked() })
                .clearTables()
                .addAllTables(model.tablesList.filter { it.objectDescriptor.asked() })
                .clearViews()
                .addAllViews(model.viewsList.filter { it.objectDescriptor.asked() })
                .build()
        return GetModelResponse.newBuilder().setModel(scoped).build()
    }

    companion object {
        const val PACKAGE = "hartland"

        fun hartlandModel(): ModelBundle {
            val lines =
                RecordedMetadata::class.java
                    .getResource("/flight/hartland-catalog.txt")!!
                    .readText()
                    .lines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
            val model = ModelBundle.newBuilder()
            lines.forEach { line ->
                val head = line.substringBefore(" [")
                val kind = line.substringAfter("[").substringBefore("]")
                val columns =
                    line
                        .substringAfter("] ", "")
                        .split(", ")
                        .filter { it.isNotBlank() }
                        .map { spec ->
                            val key = spec.endsWith("*")
                            Triple(spec.substringBefore(':'), spec.substringAfter(':').removeSuffix("*"), key)
                        }
                val (schema, namespace, name) = head.split(".")
                when {
                    schema == "er" && namespace == "entity" ->
                        model.addEntities(
                            ModelBundleEntity
                                .newBuilder()
                                .setObjectDescriptor(descriptor(SchemaCode.ER, namespace, name, "entity"))
                                .addAllAttributes(
                                    columns.map { (col, type, key) ->
                                        ModelBundleAttribute
                                            .newBuilder()
                                            .setObjectDescriptor(
                                                descriptor(
                                                    SchemaCode.ER,
                                                    namespace,
                                                    "$name.$col",
                                                    "attribute",
                                                ).toBuilder().setLocalName(col),
                                            ).setDetail(
                                                AttributeDetail
                                                    .newBuilder()
                                                    .setType(
                                                        type,
                                                    ).setIsKey(key)
                                                    .setNullable(!key),
                                            ).build()
                                    },
                                ),
                        )
                    schema == "er" && namespace == "query" ->
                        model.addNamedQueries(
                            ModelBundleQuery.newBuilder().setObjectDescriptor(
                                descriptor(SchemaCode.ER, namespace, name, "query"),
                            ),
                        )
                    schema == "db" && kind == "table" ->
                        model.addTables(
                            ModelBundleTable
                                .newBuilder()
                                .setObjectDescriptor(descriptor(SchemaCode.DB, namespace, name, "table"))
                                .setDetail(
                                    DbTableDetail
                                        .newBuilder()
                                        .addAllColumns(columns.map { (c, t, k) -> column(c, t, !k) })
                                        .addAllPrimaryKey(columns.filter { it.third }.map { it.first }),
                                ),
                        )
                    schema == "db" && kind == "view" ->
                        model.addViews(
                            ModelBundleView
                                .newBuilder()
                                .setObjectDescriptor(descriptor(SchemaCode.DB, namespace, name, "view"))
                                .setDetail(
                                    DbViewDetail.newBuilder().addAllColumns(
                                        columns.map { (c, t, _) ->
                                            column(c, t, true)
                                        },
                                    ),
                                ),
                        )
                    else -> error("unrecognised fixture line: $line")
                }
            }
            return model.build()
        }

        private fun descriptor(
            schema: SchemaCode,
            namespace: String,
            name: String,
            kind: String,
        ): ObjectDescriptor =
            ObjectDescriptor
                .newBuilder()
                .setQualifiedName(
                    QualifiedName
                        .newBuilder()
                        .setSchemaCode(schema)
                        .setNamespace(namespace)
                        .setName(name),
                ).setLocalName(name.substringAfterLast('.'))
                .setSchemaCode(schema)
                .setKind(kind)
                .setPackageName(PACKAGE)
                .build()

        private fun column(
            name: String,
            type: String,
            nullable: Boolean,
        ): DbColumnSummary =
            DbColumnSummary
                .newBuilder()
                .setName(name)
                .setDataType(type)
                .setNullable(nullable)
                .build()
    }
}
