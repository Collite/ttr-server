// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * GH #53 regression — a `fuzzy: true` flag on an **ER attribute** (not the DB column itself) must
 * resolve to its physical column through the `er2db_attribute` mapping, so the column shows up in
 * `ListObjects(kind=column, fuzzy_only=true)`.
 *
 * This exercises [MetadataServiceImpl.attributeBackedFuzzyColumns], which keys an
 * `Er2DbAttributeMapping` map by the mapping's `attribute` qname and looks up each fuzzy attribute's
 * qname. Both qnames flow from 4-part dotted references (`er.entity.<entity>.<attr>` /
 * `db.dbo.<table>.<col>`); before the `Reference.toQname` fix the 4-part branch misparsed them
 * (package = the schema token, schemaCode = UNSPECIFIED), so the lookup never matched and every
 * fuzzy attribute was silently skipped with "has no er2db column mapping".
 *
 * The pre-existing `ListObjectsFuzzyOnly*Spec` only cover `fuzzy: true` on a DB column directly,
 * which never touches this path — which is why the bug survived.
 */
class ListObjectsFuzzyAttributeMappingSpec :
    StringSpec({

        fun service(): MetadataServiceImpl {
            val dir = Files.createTempDirectory("fuzzy-attr-")
            dir.toFile().deleteOnExit()
            Files.writeString(
                dir.resolve("db.ttr"),
                """
                model db schema dbo

                def table QSTRED_DF {
                    primaryKey: ["IDSTRED"]
                    columns: [
                        def column IDSTRED { type: int, isKey: true },
                        def column KOD_STR { type: text }
                    ]
                }
                """.trimIndent(),
            )
            Files.writeString(
                dir.resolve("er.ttr"),
                """
                model er schema entity

                def entity stredisko {
                    attributes: [
                        def attribute id_strediska { type: int, isKey: true },
                        def attribute kod_strediska { type: text, search { searchable: true, fuzzy: true } }
                    ]
                }
                """.trimIndent(),
            )
            Files.writeString(
                dir.resolve("map.ttr"),
                """
                model binding

                def er2db_entity stredisko { entity: er.entity.stredisko, target: { table: db.dbo.QSTRED_DF } }
                def er2db_attribute stredisko.id_strediska {
                    attribute: er.entity.stredisko.id_strediska, target: { column: db.dbo.QSTRED_DF.IDSTRED }
                }
                def er2db_attribute stredisko.kod_strediska {
                    attribute: er.entity.stredisko.kod_strediska, target: { column: db.dbo.QSTRED_DF.KOD_STR }
                }
                """.trimIndent(),
            )

            val source =
                FileBasedSource(sourceId = "f", priority = 100, storage = LocalFsStorage(id = "f", rootPath = dir))
            val result = ModelReconciler(ModelDescriptor(id = "t", name = "t")).reconcile(listOf(source.load()))
            result.errors shouldBe emptyList()
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = PackageIndex())
        }

        "fuzzy_only=true surfaces the column backing a fuzzy ER attribute (not the column's own flag)" {
            val resp =
                service().listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(true)
                        .build(),
                )

            // KOD_STR is not tagged fuzzy itself — it's fuzzy only because kod_strediska maps to it.
            resp.itemsList.map { it.qualifiedName.name } shouldBe listOf("QSTRED_DF.KOD_STR")
        }

        "fuzzy_only=false still returns every column" {
            val resp =
                service().listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(false)
                        .build(),
                )

            resp.itemsList.map { it.qualifiedName.name }.toSet() shouldBe
                setOf("QSTRED_DF.IDSTRED", "QSTRED_DF.KOD_STR")
        }
    })
