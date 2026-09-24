// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.Er2DbAttributeMappingDetail
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class SnapshotModelHandleSpec :
    StringSpec({

        "ATTRIBUTE entries deserialize with the bare attribute name, not the qname-prefixed form" {
            // YamlImportSource prefixes attribute qnames with the entity name for uniqueness
            // (e.g. `produkt.název_produktu`) and MetadataServiceImpl sets `local_name = qname.name`.
            // SnapshotModelHandle must strip the prefix so Calcite sees the bare column name
            // (`název_produktu`) — otherwise WHERE / SELECT clauses can't reference the columns.
            val entityQn = qname(SchemaCode.ER, "entity", "produkt")
            val snapshot =
                ModelSnapshot
                    .newBuilder()
                    .addObjects(entityEntry(entityQn))
                    .addObjects(
                        attributeEntry(
                            attrQn = qname(SchemaCode.ER, "entity", "produkt.id_produktu"),
                            entityQn = entityQn,
                            type = "int",
                            isKey = true,
                            nullable = false,
                        ),
                    ).addObjects(
                        attributeEntry(
                            attrQn = qname(SchemaCode.ER, "entity", "produkt.kód_produktu"),
                            entityQn = entityQn,
                            type = "text",
                            isKey = false,
                            nullable = true,
                        ),
                    ).addObjects(
                        attributeEntry(
                            attrQn = qname(SchemaCode.ER, "entity", "produkt.název_produktu"),
                            entityQn = entityQn,
                            type = "text",
                            isKey = false,
                            nullable = true,
                        ),
                    ).build()

            val handle = SnapshotModelHandle.from(snapshot)
            val attrs = handle.attributes(entityQn)
            attrs.map { it.name } shouldContainExactlyInAnyOrder
                listOf("id_produktu", "kód_produktu", "název_produktu")

            // Attribute properties survive the strip.
            val byName = attrs.associateBy { it.name }
            byName["id_produktu"]!!.isKey shouldBe true
            byName["id_produktu"]!!.nullable shouldBe false
            byName["kód_produktu"]!!.nullable shouldBe true
            byName["název_produktu"]!!.nullable shouldBe true
        }

        "ER2DB_ATTRIBUTE_MAPPING entries populate attributeColumnRenames (column targets only)" {
            // YamlImportSource emits an Er2DbAttributeMapping per ER attribute. SnapshotModelHandle
            // must derive the entity qname from the attribute qname's prefix, strip the prefix from
            // the attribute name, and record only entries where attr name differs from column name.
            val entityQn = qname(SchemaCode.ER, "entity", "produkt")

            fun colQn(col: String) = qname(SchemaCode.DB, "dbo", "QSKUPZBOZI_DF.$col")

            fun attrMappingEntry(
                attrName: String,
                column: String,
            ): ObjectEntry {
                val attrQn = qname(SchemaCode.ER, "entity", "produkt.$attrName")
                // YamlImportSource constructs mapping qnames with first arg "map", which is not
                // in the SchemaCode enum → falls through to UNSPECIFIED. Match that here.
                val mappingQn =
                    qname(SchemaCode.SCHEMA_CODE_UNSPECIFIED, "er2db_attribute", "produkt.$attrName")
                return ObjectEntry
                    .newBuilder()
                    .setObjectDescriptor(
                        ObjectDescriptor
                            .newBuilder()
                            .setQualifiedName(mappingQn)
                            .setLocalName(mappingQn.name)
                            .setSchemaCode(mappingQn.schemaCode)
                            .setKind("map.er2db_attribute")
                            .build(),
                    ).setEr2DbAttributeMapping(
                        Er2DbAttributeMappingDetail
                            .newBuilder()
                            .setAttribute(attrQn)
                            .setColumn(colQn(column))
                            .build(),
                    ).build()
            }

            val snapshot =
                ModelSnapshot
                    .newBuilder()
                    .addObjects(entityEntry(entityQn))
                    // Three differing → recorded; one matching → omitted.
                    .addObjects(attrMappingEntry("id_produktu", "IDSKUPZBOZI"))
                    .addObjects(attrMappingEntry("kód_produktu", "KOD_SKUP_ZBOZI"))
                    .addObjects(attrMappingEntry("název_produktu", "NAZEV_SKUP_ZBOZI"))
                    .addObjects(attrMappingEntry("same_name", "same_name"))
                    .build()

            val handle = SnapshotModelHandle.from(snapshot)
            handle.attributeColumnRenames(entityQn) shouldContainExactly
                mapOf(
                    "id_produktu" to "IDSKUPZBOZI",
                    "kód_produktu" to "KOD_SKUP_ZBOZI",
                    "název_produktu" to "NAZEV_SKUP_ZBOZI",
                )
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

private fun entityEntry(entityQn: QualifiedName): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(
            ObjectDescriptor
                .newBuilder()
                .setQualifiedName(entityQn)
                .setLocalName(entityQn.name)
                .setSchemaCode(entityQn.schemaCode)
                .setKind("entity")
                .build(),
        ).setEntity(EntityDetail.newBuilder().build())
        .build()

private fun attributeEntry(
    attrQn: QualifiedName,
    entityQn: QualifiedName,
    type: String,
    isKey: Boolean,
    nullable: Boolean,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(
            ObjectDescriptor
                .newBuilder()
                .setQualifiedName(attrQn)
                .setLocalName(attrQn.name) // mirrors MetadataServiceImpl: localName = qname.name
                .setSchemaCode(attrQn.schemaCode)
                .setKind("er.attribute")
                .build(),
        ).setAttribute(
            AttributeDetail
                .newBuilder()
                .setEntity(entityQn)
                .setType(type)
                .setIsKey(isKey)
                .setNullable(nullable)
                .build(),
        ).build()
