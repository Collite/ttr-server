// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.model

import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.DbForeignKeyDetail
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.GetSnapshotResponse
import org.tatrman.meta.v1.ModelDescriptor
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.translator.framework.SurfaceType

class MetadataServiceModelHandleProviderSpec :
    StringSpec({

        fun qn(name: String) =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        val customersQn = qn("customers")
        val ordersQn = qn("orders")

        fun tableEntry(
            qn: QualifiedName,
            columns: List<Triple<String, String, Boolean>>,
            pk: List<String> = emptyList(),
        ) = ObjectEntry
            .newBuilder()
            .setObjectDescriptor(
                ObjectDescriptor
                    .newBuilder()
                    .setQualifiedName(
                        qn,
                    ).setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setKind("table")
                    .setLocalName(qn.name),
            ).setTable(
                DbTableDetail
                    .newBuilder()
                    .addAllColumns(
                        columns.map { (n, t, nul) ->
                            DbColumnSummary
                                .newBuilder()
                                .setName(n)
                                .setDataType(t)
                                .setNullable(nul)
                                .build()
                        },
                    ).addAllPrimaryKey(pk),
            ).build()

        fun fkEntry(
            from: QualifiedName,
            to: QualifiedName,
        ) = ObjectEntry
            .newBuilder()
            .setObjectDescriptor(
                ObjectDescriptor
                    .newBuilder()
                    .setKind("foreign_key")
                    .setLocalName("fk")
                    .build(),
            ).setForeignKey(DbForeignKeyDetail.newBuilder().addFromColumns(from).addToColumns(to))
            .build()

        fun snapshotResponse(version: String): GetSnapshotResponse =
            GetSnapshotResponse
                .newBuilder()
                .setEtag(version)
                .setNotModified(false)
                .setSnapshot(
                    ModelSnapshot
                        .newBuilder()
                        .setModel(ModelDescriptor.newBuilder().setId("test").setVersion(version))
                        .addObjects(
                            tableEntry(
                                customersQn,
                                listOf(Triple("id", "int", false), Triple("name", "varchar(90)", true)),
                                pk = listOf("id"),
                            ),
                        ).addObjects(
                            tableEntry(
                                ordersQn,
                                listOf(
                                    Triple("id", "bigint", false),
                                    Triple("customer_id", "bigint", true),
                                    Triple("total", "decimal(19,5)", true),
                                ),
                            ),
                        ).addObjects(fkEntry(qn("orders.customer_id"), qn("customers.id"))),
                ).build()

        "refreshOnce builds a ModelHandle from the metadata snapshot" {
            val requests = mutableListOf<GetSnapshotRequest>()
            val provider =
                MetadataServiceModelHandleProvider(
                    getSnapshot = { req ->
                        requests += req
                        snapshotResponse("v1")
                    },
                )

            // before any fetch: the boot fixture
            provider.current().currentVersion() shouldBe "boot-fixture-v0"

            provider.refreshOnce()

            requests.single().ifNoneMatch shouldBe "" // first fetch sends an empty ETag
            val handle = provider.current()
            handle.currentVersion() shouldBe "v1"
            handle.tables(SchemaCode.DB, "dbo").keys shouldContainExactlyInAnyOrder listOf(customersQn, ordersQn)
            handle.columns(customersQn).map { it.name } shouldContainExactlyInAnyOrder listOf("id", "name")
            handle.columns(customersQn).first { it.name == "id" }.surfaceType shouldBe SurfaceType.INT
            handle.columns(customersQn).first { it.name == "name" }.surfaceType shouldBe SurfaceType.TEXT
            handle.columns(ordersQn).first { it.name == "total" }.surfaceType shouldBe SurfaceType.FLOAT
            handle.foreignKeys().size shouldBe 1
            // a different (schemaCode, namespace) yields nothing
            handle.tables(SchemaCode.ER, "entity") shouldBe emptyMap()
        }

        "refreshOnce passes both placement allowlists to the snapshot handle" {
            val er = QualifiedName.newBuilder().setSchemaCode(SchemaCode.ER).setNamespace("entity")
            val snapshot = ModelSnapshot.newBuilder()
            for ((name, pkg) in listOf("obligation" to "", "verdict" to "generated", "sales" to "hartland")) {
                snapshot.addObjects(
                    ObjectEntry
                        .newBuilder()
                        .setObjectDescriptor(
                            ObjectDescriptor
                                .newBuilder()
                                .setQualifiedName(
                                    er.clone().setName(name),
                                ).setPackageName(pkg),
                        ).setEntity(
                            org.tatrman.meta.v1.EntityDetail
                                .getDefaultInstance(),
                        ),
                )
            }
            val provider =
                MetadataServiceModelHandleProvider(
                    getSnapshot = {
                        GetSnapshotResponse
                            .newBuilder()
                            .setSnapshot(snapshot)
                            .setEtag("v1")
                            .build()
                    },
                    placedPackages = setOf("generated"),
                    placedEntities = setOf("obligation"),
                )
            provider.refreshOnce()
            val handle = provider.current()
            for (name in listOf("obligation", "verdict")) {
                handle.entityMapping(er.clone().setName(name).build()) shouldBe
                    org.tatrman.translator.framework.EntityMapping.ToTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setName(name)
                            .build(),
                    )
            }
            handle.entityMapping(er.clone().setName("sales").build()) shouldBe null
        }

        "refreshOnce with not_modified keeps the current model" {
            val responses =
                ArrayDeque(
                    listOf(
                        snapshotResponse("v1"),
                        GetSnapshotResponse
                            .newBuilder()
                            .setEtag("v1")
                            .setNotModified(true)
                            .build(),
                    ),
                )
            val sentEtags = mutableListOf<String>()
            val provider =
                MetadataServiceModelHandleProvider(
                    getSnapshot = { req ->
                        sentEtags += req.ifNoneMatch
                        responses.removeFirst()
                    },
                )
            provider.refreshOnce()
            provider.current().currentVersion() shouldBe "v1"
            provider.refreshOnce()
            sentEtags shouldBe listOf("", "v1") // second fetch sends the cached ETag
            provider.current().currentVersion() shouldBe "v1" // unchanged, not the fallback
        }

        "refreshOnce keeps the fallback when no snapshot is returned" {
            val provider =
                MetadataServiceModelHandleProvider(
                    getSnapshot = {
                        GetSnapshotResponse
                            .newBuilder()
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(
                                        Severity.WARNING,
                                    ).setCode("metadata_not_ready"),
                            ).build()
                    },
                )
            provider.refreshOnce()
            provider.current().currentVersion() shouldBe "boot-fixture-v0"
        }
    })
