// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.Er2DbAttributeMappingDetail
import org.tatrman.meta.v1.Er2DbEntityMappingDetail
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.orchestrator.TranslateResult
import org.tatrman.translator.orchestrator.Translator

/**
 * GH #113 — an attribute mapped to an expression no longer takes its whole entity down.
 *
 * `store` sits on `stores(id, name, state, label)`; `display` is mapped to an expression, which
 * Veles sends with the mapping's target UNSET (the raw text has no `plan.v1.Expression` form yet).
 * Kept in the ER catalog it rode along in every scan of `store`, MAP_TO_PHYSICAL asked `stores`
 * for a `display` column, and every ER query over the entity failed with
 * `field [display] not found` — including one that never named it.
 */
class SnapshotModelHandleExpressionAttributeSpec :
    StringSpec({

        val stores = qname(SchemaCode.DB, "dbo", "stores")
        val store = qname(SchemaCode.ER, "entity", "store")

        /** The mapping of `store.<attr>`: a column of `stores`, an expression, or — as Veles sends an expression — nothing. */
        fun attributeMapping(
            attr: String,
            target: (Er2DbAttributeMappingDetail.Builder) -> Unit,
        ): ObjectEntry {
            val mappingQn = qname(SchemaCode.SCHEMA_CODE_UNSPECIFIED, "er2db_attribute", "store.$attr")
            return ObjectEntry
                .newBuilder()
                .setObjectDescriptor(descriptor(mappingQn, "map.er2db_attribute"))
                .setEr2DbAttributeMapping(
                    Er2DbAttributeMappingDetail
                        .newBuilder()
                        .setAttribute(qname(SchemaCode.ER, "entity", "store.$attr"))
                        .also(target)
                        .build(),
                ).build()
        }

        fun column(name: String): (Er2DbAttributeMappingDetail.Builder) -> Unit =
            { it.column = qname(SchemaCode.DB, "dbo", "stores.$name") }

        fun snapshot(displayTarget: (Er2DbAttributeMappingDetail.Builder) -> Unit): ModelSnapshot =
            ModelSnapshot
                .newBuilder()
                .addObjects(
                    ObjectEntry
                        .newBuilder()
                        .setObjectDescriptor(descriptor(stores, "db.table"))
                        .setTable(
                            DbTableDetail
                                .newBuilder()
                                .addColumns(col("id", "int", nullable = false))
                                .addColumns(col("name", "text"))
                                .addColumns(col("state", "text"))
                                .addColumns(col("label", "text"))
                                .addPrimaryKey("id"),
                        ),
                ).addObjects(
                    ObjectEntry
                        .newBuilder()
                        .setObjectDescriptor(descriptor(store, "entity"))
                        .setEntity(EntityDetail.getDefaultInstance()),
                ).addObjects(attribute(store, "store_id", "int", isKey = true))
                .addObjects(attribute(store, "state", "text"))
                .addObjects(attribute(store, "label", "text"))
                .addObjects(attribute(store, "display", "text"))
                .addObjects(
                    ObjectEntry
                        .newBuilder()
                        .setObjectDescriptor(
                            descriptor(
                                qname(SchemaCode.SCHEMA_CODE_UNSPECIFIED, "er2db_entity", "store"),
                                "map.er2db_entity",
                            ),
                        ).setEr2DbEntityMapping(
                            Er2DbEntityMappingDetail
                                .newBuilder()
                                .setEntity(store)
                                .setTable(stores),
                        ),
                ).addObjects(attributeMapping("store_id", column("id")))
                .addObjects(attributeMapping("state", column("state")))
                .addObjects(attributeMapping("label", column("label")))
                .addObjects(attributeMapping("display", displayTarget))
                .build()

        val asVelesSendsIt: (Er2DbAttributeMappingDetail.Builder) -> Unit = { }

        fun translate(
            handle: SnapshotModelHandle,
            sql: String,
        ): TranslateResult =
            Translator(handle).translate(
                source = sql,
                sourceLanguage = Language.SQL,
                targetLanguage = Language.SQL,
                targetSchema = SchemaCode.DB,
                targetDialect = SqlDialect.POSTGRESQL,
                sourceSchema = SchemaCode.ER,
            )

        "the expression-mapped attribute is not in its entity's ER catalog; the column-mapped ones are" {
            val handle = SnapshotModelHandle.from(snapshot(asVelesSendsIt))
            handle.attributes(store).map { it.name } shouldContainExactly listOf("store_id", "state", "label")
            handle.attributeColumnRenames(store) shouldNotContainKey "display"
        }

        "the expression arm of the oneof is left out the same way" {
            val expression: (Er2DbAttributeMappingDetail.Builder) -> Unit = {
                it.expression = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName("name")).build()
            }
            SnapshotModelHandle.from(snapshot(expression)).attributes(store).map { it.name } shouldContainExactly
                listOf("store_id", "state", "label")
        }

        "every query over the entity that does not name it translates" {
            val result =
                translate(
                    SnapshotModelHandle.from(snapshot(asVelesSendsIt)),
                    "SELECT \"store_id\", \"state\" FROM \"store\"",
                )
            val sql = result.shouldBeInstanceOf<TranslateResult.Success>().output
            sql shouldContain "\"stores\""
            sql shouldContain "\"id\" AS \"store_id\""
        }

        "a query naming it fails as an unknown column — not as an unparse failure of the whole entity" {
            val result =
                translate(SnapshotModelHandle.from(snapshot(asVelesSendsIt)), "SELECT \"display\" FROM \"store\"")
            val failure = result.shouldBeInstanceOf<TranslateResult.Failure>()
            failure.code shouldNotBe "sql_unparse_failed"
            failure.message shouldContain "display"
        }

        "an attribute with no mapping at all keeps the name = column rule" {
            // Unmapped attributes are how most estates spell `attribute name == column name`; only a
            // mapping that names no column takes an attribute out.
            val unmapped =
                snapshot(column("name"))
                    .toBuilder()
                    .addObjects(attribute(store, "name", "text"))
                    .build()
            SnapshotModelHandle.from(unmapped).attributes(store).map { it.name } shouldBe
                listOf("store_id", "state", "label", "display", "name")
        }
    })

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

private fun descriptor(
    qn: QualifiedName,
    kind: String,
): ObjectDescriptor =
    ObjectDescriptor
        .newBuilder()
        .setQualifiedName(qn)
        .setLocalName(qn.name) // mirrors MetadataServiceImpl: localName = qname.name
        .setSchemaCode(qn.schemaCode)
        .setKind(kind)
        .build()

private fun col(
    name: String,
    type: String,
    nullable: Boolean = true,
): DbColumnSummary =
    DbColumnSummary
        .newBuilder()
        .setName(name)
        .setDataType(type)
        .setNullable(nullable)
        .build()

private fun attribute(
    entity: QualifiedName,
    name: String,
    type: String,
    isKey: Boolean = false,
): ObjectEntry {
    val qn = qname(SchemaCode.ER, entity.namespace, "${entity.name}.$name")
    return ObjectEntry
        .newBuilder()
        .setObjectDescriptor(descriptor(qn, "er.attribute"))
        .setAttribute(
            AttributeDetail
                .newBuilder()
                .setEntity(entity)
                .setType(type)
                .setIsKey(isKey)
                .setNullable(!isKey),
        ).build()
}
