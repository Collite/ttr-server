// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesResponse
import org.tatrman.security.v1.TablePredicate
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.validate.client.SecurityClient

class SecurityApplierSpec :
    StringSpec({
        val customers = qname("db", "dbo", "customers")
        val orders = qname("db", "dbo", "orders")
        val tenant = eq(columnRef("tenant_id"), intLit(42))
        val region = eq(columnRef("region"), stringLit("EU"))

        "wraps a single TableScan when one predicate applies" {
            val client =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(customers)
                                .setPredicate(tenant)
                                .setRuleId("tenant_isolation"),
                        ).build()
                }
            val applier = SecurityApplier(client)
            val plan = scan(customers)
            val result = applier.apply(plan, PipelineContext.getDefaultInstance())
            result.plan.hasFilter() shouldBe true
            result.plan.filter.condition shouldBe tenant
            result.applied.size shouldBe 1
            result.applied[0].ruleId shouldBe "tenant_isolation"
        }

        "AND-merges multiple predicates that target the same table" {
            val client =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(customers)
                                .setPredicate(tenant)
                                .setRuleId("tenant_isolation"),
                        ).addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(customers)
                                .setPredicate(region)
                                .setRuleId("region_lock"),
                        ).build()
                }
            val applier = SecurityApplier(client)
            val result = applier.apply(scan(customers), PipelineContext.getDefaultInstance())
            result.plan.filter.condition
                .hasFunction() shouldBe true
            result.plan.filter.condition.function.operation shouldBe "and"
            result.applied.size shouldBe 2
        }

        "wraps each side of a join independently" {
            val client =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(customers)
                                .setPredicate(tenant)
                                .setRuleId("tenant_isolation"),
                        ).addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(orders)
                                .setPredicate(region)
                                .setRuleId("orders_region"),
                        ).build()
                }
            val applier = SecurityApplier(client)
            val plan =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        JoinNode
                            .newBuilder()
                            .setLeft(scan(customers))
                            .setRight(scan(orders))
                            .setJoinType(JoinType.INNER),
                    ).build()
            val result = applier.apply(plan, PipelineContext.getDefaultInstance())
            result.plan.join.left
                .hasFilter() shouldBe true
            result.plan.join.right
                .hasFilter() shouldBe true
        }

        "DF-V05 / G7 — emits a security_predicate_applied Warning per (table, rule) on Result.warnings" {
            val client =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(customers)
                                .setPredicate(tenant)
                                .setRuleId("tenant_isolation"),
                        ).addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(orders)
                                .setPredicate(region)
                                .setRuleId("orders_region"),
                        ).build()
                }
            val applier = SecurityApplier(client)
            val plan =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        JoinNode
                            .newBuilder()
                            .setLeft(scan(customers))
                            .setRight(scan(orders))
                            .setJoinType(JoinType.INNER),
                    ).build()
            val result = applier.apply(plan, PipelineContext.getDefaultInstance())
            result.warnings.size shouldBe 2
            result.warnings.all { it.code == "security_predicate_applied" } shouldBe true
            result.warnings.all { it.sourceStage == "security" && it.sourceService == "validator" } shouldBe true
            val messages = result.warnings.map { it.message }
            messages.any { it.contains("tenant_isolation") && it.contains("db.dbo.customers") } shouldBe true
            messages.any { it.contains("orders_region") && it.contains("db.dbo.orders") } shouldBe true
        }

        "passes through warnings from the security service" {
            val client =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addMessages(
                            ResponseMessage
                                .newBuilder()
                                .setSeverity(Severity.WARNING)
                                .setCode("unknown_table")
                                .setHumanMessage("partial"),
                        ).build()
                }
            val applier = SecurityApplier(client)
            val result = applier.apply(scan(customers), PipelineContext.getDefaultInstance())
            result.messages.size shouldBe 1
            result.messages[0].code shouldBe "unknown_table"
            result.applied.size shouldBe 0
            result.denied shouldBe false
        }

        "an ERROR from the policy engine is a denial: nothing wrapped, nothing reported applied" {
            val client =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(orders)
                                .setPredicate(region)
                                .setRuleId("orders_region"),
                        ).addMessages(
                            ResponseMessage
                                .newBuilder()
                                .setSeverity(Severity.ERROR)
                                .setCode("access_denied")
                                .setHumanMessage("denied"),
                        ).build()
                }
            val plan = scan(orders)
            val result = SecurityApplier(client).apply(plan, PipelineContext.getDefaultInstance())
            result.denied shouldBe true
            result.plan shouldBe plan
            result.applied.size shouldBe 0
            result.warnings.size shouldBe 0
            result.messages[0].code shouldBe "access_denied"
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
            if (schema ==
                "obj"
            ) {
                org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
            } else {
                org.tatrman.plan.v1.SchemaCode
                    .valueOf(schema.uppercase())
            },
        ).setNamespace(ns)
        .setName(name)
        .build()

private fun scan(table: QualifiedName): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(TableScanNode.newBuilder().setTable(table))
        .build()

private fun columnRef(name: String): Expression =
    Expression
        .newBuilder()
        .setColumnRef(ColumnRef.newBuilder().setName(name))
        .build()

private fun intLit(v: Long): Expression =
    Expression
        .newBuilder()
        .setLiteral(Literal.newBuilder().setIntValue(v).setType("int"))
        .build()

private fun stringLit(v: String): Expression =
    Expression
        .newBuilder()
        .setLiteral(Literal.newBuilder().setStringValue(v).setType("text"))
        .build()

private fun eq(
    left: Expression,
    right: Expression,
): Expression =
    Expression
        .newBuilder()
        .setFunction(
            FunctionCall
                .newBuilder()
                .setOperation("eq")
                .addOperands(left)
                .addOperands(right),
        ).setResultType("bool")
        .build()
