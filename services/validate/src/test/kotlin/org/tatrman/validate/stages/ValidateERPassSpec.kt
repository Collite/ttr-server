// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ValidatorERPassSpec :
    StringSpec({
        val customerEntity = qname("er", "entity", "customer")
        val customerTable = qname("db", "dbo", "customers")

        fun erScan(qname: QualifiedName): PlanNode =
            PlanNode
                .newBuilder()
                .setScan(
                    ScanNode
                        .newBuilder()
                        .setObject(qname)
                        .addOutputColumns(ColumnRef.newBuilder().setName("id"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("region"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("tenant_id")),
                ).build()

        fun dbScan(qname: QualifiedName): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(qname)
                        .addOutputColumns(ColumnRef.newBuilder().setName("id"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("region"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("tenant_id")),
                ).build()

        "wrapScans wraps a ScanNode(ER) with a FilterNode when isMatch returns true" {
            val plan = erScan(customerEntity)
            val predicate = regionEq("region", "EMEA")
            val isMatch = { qn: QualifiedName -> qn == customerEntity }
            val wrapped = PlanWalker.wrapScans(plan, predicate, isMatch)
            wrapped.hasFilter() shouldBe true
            wrapped.filter.input.hasScan() shouldBe true
            wrapped.filter.condition shouldBe predicate
        }

        "wrapScans leaves non-matching ScanNode untouched" {
            val plan = erScan(customerEntity)
            val predicate = regionEq("region", "EMEA")
            val isMatch = { qn: QualifiedName -> qn.schemaCode == SchemaCode.DB }
            val wrapped = PlanWalker.wrapScans(plan, predicate, isMatch)
            wrapped shouldBe plan
        }

        "wrapScans handles nested Project above ScanNode" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .addExpressions(
                                org.tatrman.plan.v1.NamedExpression
                                    .newBuilder()
                                    .setExpression(
                                        Expression
                                            .newBuilder()
                                            .setColumnRef(ColumnRef.newBuilder().setName("id")),
                                    ),
                            ).setInput(erScan(customerEntity)),
                    ).build()
            val predicate = regionEq("region", "EMEA")
            val isMatch = { qn: QualifiedName -> qn == customerEntity }
            val wrapped = PlanWalker.wrapScans(plan, predicate, isMatch)
            wrapped.hasProject() shouldBe true
            wrapped.project.input.hasFilter() shouldBe true
            wrapped.project.input.filter.input
                .hasScan() shouldBe true
        }

        "wrapScans handles multiple scans, wrapping only the matching one" {
            val customerPlan = erScan(customerEntity)
            val ordersEntity = qname("er", "entity", "orders")
            val ordersPlan = erScan(ordersEntity)
            val joinPlan =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        org.tatrman.plan.v1.JoinNode
                            .newBuilder()
                            .setJoinType(org.tatrman.plan.v1.JoinType.INNER)
                            .setLeft(customerPlan)
                            .setRight(ordersPlan)
                            .setCondition(
                                Expression
                                    .newBuilder()
                                    .setFunction(
                                        FunctionCall
                                            .newBuilder()
                                            .setOperation("eq")
                                            .addOperands(
                                                Expression
                                                    .newBuilder()
                                                    .setColumnRef(ColumnRef.newBuilder().setName("customer_id")),
                                            ).addOperands(
                                                Expression
                                                    .newBuilder()
                                                    .setColumnRef(ColumnRef.newBuilder().setName("id")),
                                            ),
                                    ).setResultType("bool"),
                            ),
                    ).build()
            val predicate = regionEq("region", "EMEA")
            val isMatch = { qn: QualifiedName -> qn == customerEntity }
            val wrapped = PlanWalker.wrapScans(joinPlan, predicate, isMatch)
            wrapped.hasJoin() shouldBe true
            wrapped.join.left.hasFilter() shouldBe true
            wrapped.join.left.filter.input
                .hasScan() shouldBe true
            wrapped.join.right.hasScan() shouldBe true
            wrapped.join.right.hasFilter() shouldBe false
        }

        "wrapScans handles DB TableScan via same code path when isMatch matches" {
            val plan = dbScan(customerTable)
            val predicate = regionEq("region", "EMEA")
            val isMatch = { qn: QualifiedName -> qn == customerTable }
            val wrapped = PlanWalker.wrapScans(plan, predicate, isMatch)
            wrapped.hasFilter() shouldBe true
            wrapped.filter.input.hasTableScan() shouldBe true
        }

        "MixedLayerDetector.hasMixedLayers detects plan with both ER ScanNode and DB TableScanNode" {
            val mixedPlan =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        org.tatrman.plan.v1.JoinNode
                            .newBuilder()
                            .setJoinType(org.tatrman.plan.v1.JoinType.INNER)
                            .setLeft(erScan(customerEntity))
                            .setRight(dbScan(customerTable))
                            .setCondition(
                                Expression
                                    .newBuilder()
                                    .setFunction(
                                        FunctionCall
                                            .newBuilder()
                                            .setOperation("eq")
                                            .addOperands(
                                                Expression
                                                    .newBuilder()
                                                    .setColumnRef(ColumnRef.newBuilder().setName("id")),
                                            ).addOperands(
                                                Expression
                                                    .newBuilder()
                                                    .setColumnRef(ColumnRef.newBuilder().setName("id")),
                                            ),
                                    ).setResultType("bool"),
                            ),
                    ).build()
            MixedLayerDetector.hasMixedLayers(mixedPlan) shouldBe true
        }

        "MixedLayerDetector.hasMixedLayers returns false for ER-only plan" {
            val erOnlyPlan = erScan(customerEntity)
            MixedLayerDetector.hasMixedLayers(erOnlyPlan) shouldBe false
        }

        "MixedLayerDetector.hasMixedLayers returns false for DB-only plan" {
            val dbOnlyPlan = dbScan(customerTable)
            MixedLayerDetector.hasMixedLayers(dbOnlyPlan) shouldBe false
        }
    })

private fun qname(
    schema: String,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(
            if (schema == "obj") {
                SchemaCode.SCHEMA_CODE_UNSPECIFIED
            } else {
                SchemaCode.valueOf(schema.uppercase())
            },
        ).setNamespace(ns)
        .setName(name)
        .build()

private fun regionEq(
    targetColumn: String,
    value: String,
): Expression =
    Expression
        .newBuilder()
        .setFunction(
            FunctionCall
                .newBuilder()
                .setOperation("eq")
                .addOperands(
                    Expression
                        .newBuilder()
                        .setColumnRef(ColumnRef.newBuilder().setName(targetColumn))
                        .setResultType("text"),
                ).addOperands(
                    Expression
                        .newBuilder()
                        .setLiteral(Literal.newBuilder().setStringValue(value).setType("text"))
                        .setResultType("text"),
                ),
        ).setResultType("bool")
        .build()
