// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

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
 * DQ — a slice of the **hartland** demo estate model (its `model/` tree), the one the Golem's query door
 * writes against: three ER entities, their DB tables, the ER→DB renames, and the two relations the
 * door's join questions cross.
 *
 * ⚑ **The relations carry NO join pairs, on purpose — that is what hartland declares.**
 * `def relation rel_catalog_sales_date { …, binding: { fk: db.dbo.fk_cs_date } }` has no `join:`
 * list, so `Source.kt` builds it with `joinPairs = []`, and every join on the estate is conditioned
 * by `JoinerPhysical`'s FK fallback after MAP_TO_PHYSICAL — not by `JoinerLogical`. A fixture that
 * gave the relation pairs would exercise a path hartland never takes. The FKs are therefore part of
 * the fixture, spelled the way `SnapshotModelHandle` passes them (`<table>.<column>` in `name`).
 *
 * Sources (hartland demo @ 7fb9108): `er/sales.ttrm` (catalog_sales), `er/calendar.ttrm` (date_dim),
 * `er/catalog.ttrm` (item), `er/relations.ttrm`, `binding/er2db.ttrm`, `db/fks.ttrm`. Columns not
 * read by any spec are left out; types follow `SnapshotModelHandle`'s surface mapping (a `decimal`
 * and a `date` arrive as FLOAT and DATETIME — the surface set has neither).
 */
internal object HartlandErFixture {
    private fun qn(
        schema: SchemaCode,
        namespace: String,
        name: String,
    ): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(schema)
            .setNamespace(namespace)
            .setName(name)
            .build()

    private fun db(name: String) = qn(SchemaCode.DB, "dbo", name)

    private fun er(name: String) = qn(SchemaCode.ER, "entity", name)

    /** One ER entity, its table, and `attribute -> column` in declaration order. */
    private data class Bound(
        val entity: ModelEntity,
        val table: ModelTable,
        val renames: Map<String, String>,
    )

    private fun bound(
        entity: String,
        table: String,
        attributes: List<Triple<String, String, SurfaceType>>,
        keys: Set<String> = emptySet(),
    ): Bound =
        Bound(
            entity =
                ModelEntity(
                    er(entity),
                    attributes.map { (attr, _, type) ->
                        ModelAttribute(attr, type, nullable = attr !in keys, isKey = attr in keys)
                    },
                ),
            table =
                ModelTable(
                    db(table),
                    attributes.map { (attr, column, type) -> ModelColumn(column, type, nullable = attr !in keys) },
                    primaryKey = attributes.filter { it.first in keys }.map { it.second },
                ),
            renames = attributes.associate { (attr, column, _) -> attr to column },
        )

    private val catalogSales =
        bound(
            "catalog_sales",
            "catalog_sales",
            listOf(
                Triple("item", "cs_item_sk", SurfaceType.INT),
                Triple("order_number", "cs_order_number", SurfaceType.INT),
                Triple("sold_date", "cs_sold_date_sk", SurfaceType.INT),
                Triple("quantity", "cs_quantity", SurfaceType.INT),
                Triple("ext_sales_price", "cs_ext_sales_price", SurfaceType.FLOAT),
            ),
            keys = setOf("item", "order_number"),
        )

    private val dateDim =
        bound(
            "date_dim",
            "date_dim",
            listOf(
                Triple("sk", "d_date_sk", SurfaceType.INT),
                Triple("cal_date", "d_date", SurfaceType.DATETIME),
                Triple("year", "d_year", SurfaceType.INT),
                Triple("month", "d_moy", SurfaceType.INT),
            ),
            keys = setOf("sk"),
        )

    private val item =
        bound(
            "item",
            "item",
            listOf(
                Triple("sk", "i_item_sk", SurfaceType.INT),
                Triple("item_id", "i_item_id", SurfaceType.TEXT),
                Triple("product_name", "i_product_name", SurfaceType.TEXT),
                Triple("brand", "i_brand", SurfaceType.TEXT),
                Triple("class", "i_class", SurfaceType.TEXT),
            ),
            keys = setOf("sk"),
        )

    private val all = listOf(catalogSales, dateDim, item)

    private fun column(
        table: String,
        column: String,
    ) = db("$table.$column")

    private val foreignKeys =
        listOf(
            // db/fks.ttrm: fk_cs_date, fk_cs_item
            ModelForeignKey(
                listOf(column("catalog_sales", "cs_sold_date_sk")),
                listOf(column("date_dim", "d_date_sk")),
            ),
            ModelForeignKey(listOf(column("catalog_sales", "cs_item_sk")), listOf(column("item", "i_item_sk"))),
        )

    private val relations =
        listOf(
            // er/relations.ttrm: rel_catalog_sales_date, rel_catalog_sales_item — FK-bound, no `join:`.
            ModelRelation(er("catalog_sales"), er("date_dim"), joinPairs = emptyList()),
            ModelRelation(er("catalog_sales"), er("item"), joinPairs = emptyList()),
        )

    fun handle(): ModelHandle =
        object : ModelHandle {
            override fun tables(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelTable> =
                all
                    .map { it.table }
                    .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
                    .associateBy { it.qname }

            override fun columns(tableQname: QualifiedName): List<ModelColumn> =
                all.firstOrNull { it.table.qname == tableQname }?.table?.columns ?: emptyList()

            override fun foreignKeys(): List<ModelForeignKey> = foreignKeys

            override fun entities(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelEntity> =
                all
                    .map { it.entity }
                    .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
                    .associateBy { it.qname }

            override fun attributes(entityQname: QualifiedName): List<ModelAttribute> =
                all.firstOrNull { it.entity.qname == entityQname }?.entity?.attributes ?: emptyList()

            override fun relations(): List<ModelRelation> = relations

            override fun entityMapping(entityQname: QualifiedName): EntityMapping? =
                all.firstOrNull { it.entity.qname == entityQname }?.let {
                    EntityMapping.ToTable(
                        it.table.qname,
                        whereFilter = null,
                    )
                }

            override fun attributeColumnRenames(entityQname: QualifiedName): Map<String, String> =
                all.firstOrNull { it.entity.qname == entityQname }?.renames ?: emptyMap()

            override fun savedQueries(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

            override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
                error("no saved queries in the hartland fixture")

            override fun currentVersion(): String = "hartland-er-fixture-v1"

            override fun namespaces(schemaCode: SchemaCode): Set<String> =
                when (schemaCode) {
                    SchemaCode.DB -> setOf("dbo")
                    SchemaCode.ER -> setOf("entity")
                    else -> emptySet()
                }
        }
}
