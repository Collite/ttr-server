// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.orchestrator.TranslateResult
import org.tatrman.translator.orchestrator.Translator

/**
 * An ER attribute's type comes from the model's own type names (`decimal`, `date`, `char`, …), as a
 * table column's does — not only from the five DSL tags.
 *
 * Regression: `SurfaceType.fromTag` knew only TEXT/INT/FLOAT/BOOL/DATETIME, so every `decimal`
 * attribute became TEXT. SUM over it was implicitly CAST to DECIMAL(19, 9) (Calcite's default
 * DECIMAL precision), and once a CASE or COALESCE re-cast the sum, Postgres refused every total of
 * 10^10 and up: `numeric field overflow — precision 19, scale 9`.
 */
class SnapshotAttributeTypesSpec :
    StringSpec({
        val entityQn = qn(SchemaCode.ER, "entity", "sale")
        val tableQn = qn(SchemaCode.DB, "dbo", "sales")

        fun snapshot(vararg attrs: Pair<String, String>): ModelSnapshot =
            ModelSnapshot
                .newBuilder()
                .addObjects(entity(entityQn))
                .apply { attrs.forEach { (name, type) -> addObjects(attribute(entityQn, name, type)) } }
                .build()

        "the model's type names map to their surface type, as a table column's do" {
            val handle =
                SnapshotModelHandle.from(
                    snapshot(
                        "amount" to "decimal",
                        "rate" to "decimal(10,2)",
                        "day" to "date",
                        "n" to "integer",
                        "k" to "int",
                        "code" to "char",
                        "name" to "text",
                        "odd" to "geography",
                    ),
                )
            handle.attributes(entityQn).associate { it.name to it.surfaceType } shouldBe
                mapOf(
                    "amount" to SurfaceType.FLOAT,
                    "rate" to SurfaceType.FLOAT,
                    "day" to SurfaceType.DATETIME,
                    "n" to SurfaceType.INT,
                    "k" to SurfaceType.INT,
                    "code" to SurfaceType.TEXT,
                    "name" to SurfaceType.TEXT,
                    "odd" to SurfaceType.TEXT,
                )
        }

        // -- end to end: the snapshot handle under the translator -------------------------------

        val mapped =
            SnapshotModelHandle.from(
                ModelSnapshot
                    .newBuilder()
                    .addObjects(table(tableQn, "cs_price" to "numeric(10,2)", "cs_sold" to "date"))
                    .addObjects(entity(entityQn))
                    .addObjects(attribute(entityQn, "price", "decimal"))
                    .addObjects(attribute(entityQn, "sold", "date"))
                    .addObjects(entityMapping(entityQn, tableQn))
                    .addObjects(attributeMapping(entityQn, "price", tableQn, "cs_price"))
                    .addObjects(attributeMapping(entityQn, "sold", tableQn, "cs_sold"))
                    .build(),
            )

        fun postgres(sql: String): String {
            val r =
                Translator(mapped).translate(
                    source = sql,
                    sourceLanguage = Language.SQL,
                    targetLanguage = Language.SQL,
                    targetSchema = SchemaCode.DB,
                    targetDialect = SqlDialect.POSTGRESQL,
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            return r.output
        }

        "COALESCE over a SUM of a decimal attribute is not cast to DECIMAL(19, 9)" {
            val sql = postgres("SELECT COALESCE(SUM(price), 0) AS total FROM er.entity.sale")
            sql shouldNotContain "DECIMAL(19, 9)"
            sql shouldContain "SUM(\"cs_price\")"
        }

        "a date attribute still compares with a string literal and with a DATE literal" {
            postgres("SELECT SUM(price) AS total FROM er.entity.sale WHERE sold >= '2025-07-01'") shouldContain
                "cs_sold"
            postgres("SELECT SUM(price) AS total FROM er.entity.sale WHERE sold >= DATE '2025-07-01'") shouldContain
                "cs_sold"
        }
    })

private fun qn(
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
        .setLocalName(qn.name)
        .setSchemaCode(qn.schemaCode)
        .setKind(kind)
        .build()

private fun entity(entityQn: QualifiedName): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(descriptor(entityQn, "entity"))
        .setEntity(EntityDetail.newBuilder().build())
        .build()

// Attribute qnames are entity-prefixed, as YamlImportSource writes them (`sale.price`).
private fun attribute(
    entityQn: QualifiedName,
    name: String,
    type: String,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(descriptor(qn(SchemaCode.ER, "entity", "${entityQn.name}.$name"), "er.attribute"))
        .setAttribute(
            AttributeDetail
                .newBuilder()
                .setEntity(entityQn)
                .setType(type)
                .setNullable(true)
                .build(),
        ).build()

private fun table(
    tableQn: QualifiedName,
    vararg columns: Pair<String, String>,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(descriptor(tableQn, "db.table"))
        .setTable(
            DbTableDetail
                .newBuilder()
                .apply {
                    columns.forEach { (name, type) ->
                        addColumns(
                            DbColumnSummary
                                .newBuilder()
                                .setName(name)
                                .setDataType(type)
                                .setNullable(true),
                        )
                    }
                }.build(),
        ).build()

private fun entityMapping(
    entityQn: QualifiedName,
    tableQn: QualifiedName,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(
            descriptor(qn(SchemaCode.SCHEMA_CODE_UNSPECIFIED, "er2db_entity", entityQn.name), "map.er2db_entity"),
        ).setEr2DbEntityMapping(
            Er2DbEntityMappingDetail
                .newBuilder()
                .setEntity(entityQn)
                .setTable(tableQn)
                .build(),
        ).build()

private fun attributeMapping(
    entityQn: QualifiedName,
    attr: String,
    tableQn: QualifiedName,
    column: String,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(
            descriptor(
                qn(SchemaCode.SCHEMA_CODE_UNSPECIFIED, "er2db_attribute", "${entityQn.name}.$attr"),
                "map.er2db_attribute",
            ),
        ).setEr2DbAttributeMapping(
            Er2DbAttributeMappingDetail
                .newBuilder()
                .setAttribute(qn(SchemaCode.ER, "entity", "${entityQn.name}.$attr"))
                .setColumn(qn(SchemaCode.DB, tableQn.namespace, "${tableQn.name}.$column"))
                .build(),
        ).build()
