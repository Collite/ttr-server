// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.RowWrite
import org.tatrman.plan.v1.RowWrites
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode

class PlanNamespacesSpec :
    StringSpec({
        "plan namespaces are collected from nested reads and row targets without leaking between requests" {
            val handle = SnapshotModelHandle.from(ModelSnapshot.getDefaultInstance())

            fun qn(
                code: SchemaCode,
                ns: String,
            ) = QualifiedName
                .newBuilder()
                .setSchemaCode(code)
                .setNamespace(ns)
                .setName("table")
                .build()

            fun scan(ns: String) =
                PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(qn(SchemaCode.DB, ns))).build()
            val nested = PlanNode.newBuilder().setProject(ProjectNode.newBuilder().setInput(scan("tenant_one"))).build()
            val first = withPlanNamespaces(handle, nested)
            first.namespaces(SchemaCode.DB) shouldBe setOf("tenant_one")
            val writes =
                PlanNode
                    .newBuilder()
                    .setRowWrites(
                        RowWrites
                            .newBuilder()
                            .addRows(RowWrite.newBuilder().setTarget(qn(SchemaCode.DB, "tenant_two")))
                            .addRows(RowWrite.newBuilder().setTarget(qn(SchemaCode.ER, "entity")))
                            .addRows(RowWrite.newBuilder().setTarget(qn(SchemaCode.DB, ""))),
                    ).build()
            withPlanNamespaces(handle, writes).namespaces(SchemaCode.DB) shouldBe setOf("tenant_two")
            handle.namespaces(SchemaCode.DB) shouldBe emptySet()
            first.namespaces(SchemaCode.DB) shouldBe setOf("tenant_one")
            first.currentVersion() shouldBe handle.currentVersion()
            first.foreignKeys() shouldBe handle.foreignKeys()
            first.columns(qn(SchemaCode.DB, "tenant_one")) shouldBe handle.columns(qn(SchemaCode.DB, "tenant_one"))
        }
    })
