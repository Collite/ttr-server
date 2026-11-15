// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translate.v1.ExplainRequest
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.UnparseRequest

class PlacedReadSpec :
    StringSpec({
        val er =
            QualifiedName
                .newBuilder()
                .setSchemaCode(
                    SchemaCode.ER,
                ).setNamespace("entity")
                .setName("obligation")
                .build()
        val snapshot =
            ModelSnapshot
                .newBuilder()
                .addObjects(
                    ObjectEntry
                        .newBuilder()
                        .setObjectDescriptor(ObjectDescriptor.newBuilder().setQualifiedName(er))
                        .setEntity(EntityDetail.getDefaultInstance()),
                ).addObjects(
                    ObjectEntry
                        .newBuilder()
                        .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName("obligation.id"))
                        .setAttribute(
                            AttributeDetail
                                .newBuilder()
                                .setEntity(er)
                                .setType("text")
                                .setIsKey(true),
                        ),
                ).build()
        val handle = SnapshotModelHandle.from(snapshot, placedEntities = setOf("obligation"))
        val service = TranslatorServiceImpl(StaticModelHandleProvider(handle))

        fun plan(ns: String) =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(SchemaCode.DB)
                                .setNamespace(ns)
                                .setName("obligation"),
                        ).addOutputColumns(ColumnRef.newBuilder().setName("id").setType("text")),
                ).build()

        "unparse and REL_NODE parse/translate use the request namespace without changing the snapshot" {
            for (ns in listOf("cz", "tenant_other")) {
                val physical = plan(ns)
                val unparse =
                    service.unparseFromRelNode(
                        UnparseRequest
                            .newBuilder()
                            .setPlan(physical)
                            .setTargetLanguage(Language.SQL)
                            .setTargetDialect(SqlDialect.POSTGRESQL)
                            .build(),
                    )
                unparse.messagesCount shouldBe 0
                unparse.output shouldContain "\"obligation\""
                val source = String(physical.toByteArray(), Charsets.ISO_8859_1)
                val parse =
                    service.parseToRelNode(
                        ParseRequest
                            .newBuilder()
                            .setSource(source)
                            .setSourceLanguage(Language.REL_NODE)
                            .setTargetSchema(SchemaCode.DB)
                            .build(),
                    )
                parse.messagesCount shouldBe 0
                val translated =
                    service.translate(
                        TranslateRequest
                            .newBuilder()
                            .setSource(source)
                            .setSourceLanguage(Language.REL_NODE)
                            .setTargetLanguage(
                                Language.SQL,
                            ).setTargetSchema(SchemaCode.DB)
                            .setTargetDialect(SqlDialect.POSTGRESQL)
                            .build(),
                    )
                translated.messagesCount shouldBe 0
                translated.output shouldContain "\"obligation\""
            }
            handle.namespaces(SchemaCode.DB) shouldBe setOf("")
        }

        "Explain parse resolves the same concrete placed read as parse" {
            val result =
                service.explain(
                    ExplainRequest
                        .newBuilder()
                        .setParse(
                            ParseRequest
                                .newBuilder()
                                .setSource(String(plan("cz").toByteArray(), Charsets.ISO_8859_1))
                                .setSourceLanguage(Language.REL_NODE)
                                .setTargetSchema(SchemaCode.DB),
                        ).build(),
                )
            result.messagesCount shouldBe 0
            (result.stagesCount > 0) shouldBe true
        }

        "a placed read without a concrete namespace is refused instead of guessing the default" {
            val result =
                service.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(plan(""))
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.POSTGRESQL)
                        .build(),
                )
            result.messagesList.single().code shouldBe "sql_unparse_failed"
        }
    })
