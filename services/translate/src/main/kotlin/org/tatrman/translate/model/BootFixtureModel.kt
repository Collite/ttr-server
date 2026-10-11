// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.model

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType

/**
 * Tiny boot-time fixture model used when `translate.use-fixture-model = true`.
 * Lets the service start up and serve trivial requests when no metadata service
 * is reachable (development, smoke tests, CI). Production deployments fall
 * back to this only if the metadata client fails to load — see Application.kt.
 */
object BootFixtureModel {
    private fun qname(
        schema: SchemaCode,
        ns: String,
        name: String,
    ): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(schema)
            .setNamespace(ns)
            .setName(name)
            .build()

    // The Hartland demo model's shape: `er.entity.customer` maps to `db.dbo.customer`.
    private val customerTable =
        ModelTable(
            qname = qname(SchemaCode.DB, "dbo", "customer"),
            columns =
                listOf(
                    ModelColumn("id", SurfaceType.INT, nullable = false),
                    ModelColumn("name", SurfaceType.TEXT, nullable = true),
                ),
            primaryKey = listOf("id"),
        )

    // Aligned with the `query-runquery` MSSQL seed (olymp platform/data/mssql:
    // dbo.sample_orders — id/tenant_id/region/amount, 4 rows incl. tenant_id 't-alpha').
    // Lets the raw-SQL `query` path resolve + unparse against the fixture model so
    // RunQueryIntegrationSpec's result assertion runs end-to-end through Mssql → MSSQL
    // (SurfaceType has no DECIMAL; FLOAT is the surface type for the amount column — a bare
    // projection never coerces, so MSSQL returns the real DECIMAL(18,2) values as-is).
    private val sampleOrders =
        ModelTable(
            qname = qname(SchemaCode.DB, "dbo", "sample_orders"),
            columns =
                listOf(
                    ModelColumn("id", SurfaceType.INT, nullable = false),
                    ModelColumn("tenant_id", SurfaceType.TEXT, nullable = false),
                    ModelColumn("region", SurfaceType.TEXT, nullable = false),
                    ModelColumn("amount", SurfaceType.FLOAT, nullable = false),
                ),
            primaryKey = listOf("id"),
        )

    private val dbTables = listOf(customerTable, sampleOrders)

    private val customerEntity =
        ModelEntity(
            qname = qname(SchemaCode.ER, "entity", "customer"),
            attributes =
                listOf(
                    ModelAttribute("id", SurfaceType.INT, nullable = false, isKey = true),
                    ModelAttribute("name", SurfaceType.TEXT, nullable = true),
                ),
        )

    fun handle(): ModelHandle =
        object : ModelHandle {
            override fun tables(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelTable> =
                dbTables
                    .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
                    .associateBy { it.qname }

            override fun columns(tableQname: QualifiedName): List<ModelColumn> =
                dbTables.firstOrNull { it.qname == tableQname }?.columns ?: emptyList()

            override fun foreignKeys(): List<ModelForeignKey> = emptyList()

            override fun entities(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelEntity> =
                listOf(customerEntity)
                    .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
                    .associateBy { it.qname }

            override fun attributes(entityQname: QualifiedName): List<ModelAttribute> =
                if (entityQname == customerEntity.qname) customerEntity.attributes else emptyList()

            override fun relations(): List<ModelRelation> = emptyList()

            override fun entityMapping(entityQname: QualifiedName): EntityMapping? =
                if (entityQname == customerEntity.qname) {
                    EntityMapping.ToTable(table = customerTable.qname, whereFilter = null)
                } else {
                    null
                }

            override fun savedQueries(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

            override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
                error("No saved queries in BootFixtureModel")

            override fun currentVersion(): String = "boot-fixture-v0"

            override fun namespaces(schemaCode: SchemaCode): Set<String> =
                when (schemaCode) {
                    SchemaCode.DB -> setOf("dbo")
                    SchemaCode.ER -> setOf("entity")
                    SchemaCode.OBJ -> setOf("query")
                    else -> emptySet()
                }
        }
}
