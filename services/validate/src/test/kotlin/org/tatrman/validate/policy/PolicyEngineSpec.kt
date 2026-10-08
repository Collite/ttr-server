// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.common.v1.Severity

class PolicyEngineSpec :
    StringSpec({

        fun customersScan(): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                .setNamespace("dbo")
                                .setName("customers"),
                        ),
                ).build()

        fun table(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        fun scanOf(table: QualifiedName): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(TableScanNode.newBuilder().setTable(table))
                .build()

        fun join(
            left: PlanNode,
            right: PlanNode,
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setJoin(
                    org.tatrman.plan.v1.JoinNode
                        .newBuilder()
                        .setLeft(left)
                        .setRight(right)
                        .setJoinType(org.tatrman.plan.v1.JoinType.INNER),
                ).build()

        fun request(
            plan: PlanNode,
            userId: String,
        ): EvaluatePoliciesRequest =
            EvaluatePoliciesRequest
                .newBuilder()
                .setPlan(plan)
                .setContext(PipelineContext.newBuilder().setUserId(userId))
                .build()

        "tenant_isolation policy fires on a db.dbo table for a tenant:user identity" {
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val resp = service.evaluatePolicies(request(customersScan(), userId = "tenant-7:alice"))

            resp.predicatesList shouldHaveSize 1
            val p = resp.predicatesList[0]
            p.ruleId shouldBe "tenant_isolation"
            p.table.name shouldBe "customers"
            p.predicate.function.operation shouldBe "eq"
            // The literal we substituted is the tenant prefix from the user_id.
            p.predicate.function.operandsList[1]
                .literal.stringValue shouldBe "tenant-7"
        }

        // LR C-5·2 — fail closed. This case used to SKIP the policy with a `policy_evaluation_skipped`
        // warning and let the query run without its predicate: a caller the policy could not be
        // evaluated for saw every row it was meant to restrict.
        "an applicable policy whose user attribute is unresolvable denies the request" {
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val resp = service.evaluatePolicies(request(customersScan(), userId = "alice-no-tenant"))

            resp.predicatesList shouldHaveSize 0
            resp.messagesList.map { it.code } shouldBe listOf("access_denied", "policy_unresolvable_attribute")
            resp.messagesList.all { it.severity == Severity.ERROR } shouldBe true
            resp.messagesList[1].humanMessage shouldContain "tenant_isolation"
            resp.messagesList[1].humanMessage shouldContain "tenant_id"
        }

        "the denial is the FIRST message even when a warning was raised before it" {
            // golem (and any MCP caller) reads the first message's code as the rejection's code.
            val unknownTables = StaticPolicyMetadataClient(version = "", knownQnames = emptySet())
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core), unknownTables)
            val resp = service.evaluatePolicies(request(customersScan(), userId = "alice-no-tenant"))

            resp.messagesList.map { it.code } shouldBe
                listOf("access_denied", "policy_unresolvable_attribute", "unknown_table")
        }

        "a denial on one table drops every predicate — the request is refused, not narrowed" {
            val orders = table("orders")
            val registry =
                PolicyRegistry(
                    listOf(
                        Policy(
                            id = "orders_open",
                            tableMatch = TableMatcher.Exact(orders),
                            predicate = PolicyPredicate.Eq("status", PolicyValue.Literal("OPEN", "text")),
                        ),
                        Policy(
                            id = "customers_region",
                            tableMatch = TableMatcher.Exact(table("customers")),
                            predicate = PolicyPredicate.Eq("region", PolicyValue.UserAttribute("region")),
                            columnRules = listOf(ColumnRule("ssn", ColumnAction.Deny)),
                        ),
                    ),
                )
            val resp =
                PolicyEngine(
                    registry,
                ).evaluatePolicies(request(join(customersScan(), scanOf(orders)), "t:alice"))

            resp.predicatesList shouldHaveSize 0
            resp.columnRulesList shouldHaveSize 0
            resp.messagesList[0].code shouldBe "access_denied"
        }

        "an unresolvable attribute on a policy that does not apply to the caller is no denial" {
            val registry =
                PolicyRegistry(
                    listOf(
                        Policy(
                            id = "region_scope",
                            tableMatch = TableMatcher.Exact(table("customers")),
                            predicate = PolicyPredicate.Eq("region", PolicyValue.UserAttribute("region")),
                            roles = listOf("regional-manager"),
                        ),
                    ),
                )
            val resp = PolicyEngine(registry).evaluatePolicies(request(customersScan(), "t:alice"))

            resp.predicatesList shouldHaveSize 0
            resp.messagesList shouldHaveSize 0
        }

        "no policies apply outside the configured namespace" {
            val erQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("Customer")
                    .build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setTableScan(TableScanNode.newBuilder().setTable(erQname))
                    .build()
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val resp = service.evaluatePolicies(request(plan, userId = "tenant-7:alice"))
            resp.predicatesList shouldHaveSize 0
            resp.messagesList shouldHaveSize 0
        }

        "JOIN over two tables produces a predicate per matching table" {
            val ordersScan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        TableScanNode.newBuilder().setTable(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                .setNamespace("dbo")
                                .setName("orders"),
                        ),
                    ).build()
            val joinPlan =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        org.tatrman.plan.v1.JoinNode
                            .newBuilder()
                            .setLeft(customersScan())
                            .setRight(ordersScan)
                            .setJoinType(org.tatrman.plan.v1.JoinType.INNER),
                    ).build()
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            val resp = service.evaluatePolicies(request(joinPlan, userId = "tenant-7:alice"))
            resp.predicatesList shouldHaveSize 2
            resp.predicatesList.map { it.table.name }.toSet() shouldBe setOf("customers", "orders")
        }

        "engine reports loaded policy count" {
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core))
            service.loadedPolicies() shouldBe DefaultPolicies.core.size
        }

        "Eq + And + In + Not predicates render to expected operator codes" {
            val identity = ResolvedIdentity(userId = "u", attributes = mapOf("tenant_id" to "t1"))
            val and =
                PolicyPredicate.And(
                    left = PolicyPredicate.Eq("a", PolicyValue.Literal(1, "int")),
                    right =
                        PolicyPredicate.Not(
                            PolicyPredicate.In(
                                column = "b",
                                values = listOf(PolicyValue.Literal("x", "text"), PolicyValue.Literal("y", "text")),
                            ),
                        ),
                )
            val expr: Expression = PolicyToExpression.convert(and, identity)
            expr.function.operation shouldBe "and"
            expr.function.operandsList[0]
                .function.operation shouldBe "eq"
            expr.function.operandsList[1]
                .function.operation shouldBe "not"
            // Phase 08 B4 / DF-S05 — IN is now a first-class `in` FunctionCall:
            //   { operation: "in", operands: [column_ref(b), lit("x"), lit("y")] }
            // (was an OR-tree of equalities pre-B4).
            val inSide =
                expr.function.operandsList[1]
                    .function.operandsList[0]
            inSide.function.operation shouldBe "in"
            inSide.function.operandsList.size shouldBe 3 // column + 2 values
            inSide.function.operandsList[0]
                .columnRef.name shouldBe "b"
            inSide.function.operandsList[1]
                .literal.stringValue shouldBe "x"
            inSide.function.operandsList[2]
                .literal.stringValue shouldBe "y"
        }

        "IN with empty values still degrades to literal false (Calcite rejects zero-operand IN)" {
            val identity = ResolvedIdentity(userId = "u", attributes = emptyMap())
            val pred = PolicyPredicate.In(column = "x", values = emptyList())
            val expr = PolicyToExpression.convert(pred, identity)
            expr.hasLiteral() shouldBe true
            expr.literal.boolValue shouldBe false
        }
    })
