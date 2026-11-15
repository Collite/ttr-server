// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.StoreNode
import org.tatrman.plan.v1.SubqueryExpression
import org.tatrman.plan.v1.ValuesNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.RowWrite
import org.tatrman.plan.v1.RowWrites
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode

class PlanNamespacesSpec :
    StringSpec({
        "SCAN and STORE namespaces include expression subqueries" {
            val handle = SnapshotModelHandle.from(ModelSnapshot.getDefaultInstance())

            fun qn(ns: String) =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace(ns)
                    .setName("x")
                    .build()
            val scan = PlanNode.newBuilder().setScan(ScanNode.newBuilder().setObject(qn("tenant"))).build()
            val store =
                PlanNode
                    .newBuilder()
                    .setStore(
                        StoreNode
                            .newBuilder()
                            .setTarget(qn("cz"))
                            .setInput(PlanNode.newBuilder().setValues(ValuesNode.getDefaultInstance())),
                    ).build()
            withPlanNamespaces(handle, scan).namespaces(SchemaCode.DB) shouldBe setOf("tenant")
            withPlanNamespaces(handle, store).namespaces(SchemaCode.DB) shouldBe setOf("cz")
            val filter =
                PlanNode
                    .newBuilder()
                    .setFilter(
                        FilterNode.newBuilder().setInput(store).setCondition(
                            Expression.newBuilder().setSubquery(
                                SubqueryExpression.newBuilder().setKind("in").setSubquery(scan),
                            ),
                        ),
                    ).build()
            withPlanNamespaces(handle, filter).namespaces(SchemaCode.DB) shouldBe setOf("cz", "tenant")
        }

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
