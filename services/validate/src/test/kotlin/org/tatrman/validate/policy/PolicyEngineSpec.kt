// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.StoreNode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.WriteMode
import org.tatrman.security.v1.EvaluatePoliciesRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.common.v1.Severity
import java.util.concurrent.CopyOnWriteArrayList

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
            val staleModel = StaticPolicyMetadataClient(version = "v-live")
            val service = PolicyEngine(PolicyRegistry(DefaultPolicies.core), staleModel)
            val resp =
                service.evaluatePolicies(
                    EvaluatePoliciesRequest
                        .newBuilder()
                        .setPlan(customersScan())
                        .setContext(PipelineContext.newBuilder().setUserId("alice-no-tenant").setModelVersion("v-old"))
                        .build(),
                )

            resp.messagesList.map { it.code } shouldBe
                listOf("access_denied", "policy_unresolvable_attribute", "model_version_mismatch")
        }

        "a refused request looks no table up; an allowed one names an unknown table as db.dbo.x" {
            val lookups = CopyOnWriteArrayList<QualifiedName>()
            val counting =
                object : PolicyMetadataClient {
                    override suspend fun objectExists(qname: QualifiedName): Boolean {
                        lookups += qname
                        return false
                    }

                    override suspend fun currentVersion(): String = ""
                }
            val engine = PolicyEngine(PolicyRegistry(DefaultPolicies.core), counting)

            engine.evaluatePolicies(request(customersScan(), userId = "alice-no-tenant"))
            lookups shouldHaveSize 0

            val allowed = engine.evaluatePolicies(request(customersScan(), userId = "t:alice"))
            lookups shouldHaveSize 1
            allowed.messagesList.single().humanMessage shouldContain "'db.dbo.customers'"
        }

        // review-159 ⑧ — a write target is not a scan: no filter can narrow which rows a write hits.
        "a row policy on a table the plan WRITES refuses the write — policy_restricted_write" {
            val store =
                PlanNode
                    .newBuilder()
                    .setStore(
                        StoreNode
                            .newBuilder()
                            .setTarget(table("inventory"))
                            .setInput(scanOf(table("staging")))
                            .setMode(WriteMode.REPLACE),
                    ).build()
            val resp = PolicyEngine(PolicyRegistry(DefaultPolicies.core)).evaluatePolicies(request(store, "t:alice"))

            resp.predicatesList shouldHaveSize 0
            resp.messagesList.map { it.code } shouldBe
                listOf("access_denied", "policy_restricted_write")
            resp.messagesList[1].humanMessage shouldContain "'db.dbo.inventory'"
        }

        "a write to a table no policy covers for this caller is no denial" {
            val other = table("staging")
            val registry =
                PolicyRegistry(
                    listOf(
                        Policy(
                            id = "inventory_scope",
                            tableMatch = TableMatcher.Exact(table("inventory")),
                            predicate = PolicyPredicate.Eq("w", PolicyValue.Literal(5, "int")),
                        ),
                    ),
                )
            val store =
                PlanNode
                    .newBuilder()
                    .setStore(StoreNode.newBuilder().setTarget(other).setInput(scanOf(table("source"))))
                    .build()
            val resp = PolicyEngine(registry).evaluatePolicies(request(store, "t:alice"))

            resp.messagesList.none { it.severity == Severity.ERROR } shouldBe true
        }

        // review-159 ⑭ — any failure to evaluate an applicable policy refuses; it never escapes as
        // an exception the service would report "unavailable" (and the query service retry).
        "a policy that fails to evaluate for an unexpected cause refuses — policy_evaluation_failed" {
            val broken =
                object {
                    override fun toString(): String = error("no text form")
                }
            val registry =
                PolicyRegistry(
                    listOf(
                        Policy(
                            id = "broken",
                            tableMatch = TableMatcher.Exact(table("customers")),
                            predicate = PolicyPredicate.Eq("x", PolicyValue.Literal(broken, "text")),
                        ),
                    ),
                )
            val resp = PolicyEngine(registry).evaluatePolicies(request(customersScan(), "t:alice"))

            resp.predicatesList shouldHaveSize 0
            resp.messagesList.map { it.code } shouldBe listOf("access_denied", "policy_evaluation_failed")
        }

        // review-159 ④ — a MASK always travels with the expression to mask the column with.
        "a MASK's value: a literal, a caller attribute resolved per call, or NULL when it names none" {
            val registry =
                PolicyRegistry(
                    listOf(
                        Policy(
                            id = "masks",
                            tableMatch = TableMatcher.Exact(table("customers")),
                            predicate = PolicyPredicate.Eq("tenant_id", PolicyValue.UserAttribute("tenant_id")),
                            columnRules =
                                listOf(
                                    ColumnRule("ssn", ColumnAction.Mask(PolicyValue.Literal("***", "text"))),
                                    ColumnRule("owner", ColumnAction.Mask(PolicyValue.UserAttribute("user_id"))),
                                    ColumnRule("salary", ColumnAction.Mask()),
                                ),
                        ),
                    ),
                )
            val rules =
                PolicyEngine(registry)
                    .evaluatePolicies(request(customersScan(), "t:alice"))
                    .columnRulesList
                    .associate { it.column to it.maskExpression.literal }

            rules.getValue("ssn").stringValue shouldBe "***"
            rules.getValue("owner").stringValue shouldBe "alice"
            rules.getValue("salary").isNull shouldBe true
        }

        "a MASK whose caller attribute cannot be resolved refuses, like a predicate's" {
            val registry =
                PolicyRegistry(
                    listOf(
                        Policy(
                            id = "region_mask",
                            tableMatch = TableMatcher.Exact(table("customers")),
                            predicate = PolicyPredicate.Eq("open", PolicyValue.Literal(true, "bool")),
                            columnRules =
                                listOf(
                                    ColumnRule("ssn", ColumnAction.Mask(PolicyValue.UserAttribute("region"))),
                                ),
                        ),
                    ),
                )
            val resp = PolicyEngine(registry).evaluatePolicies(request(customersScan(), "t:alice"))

            resp.columnRulesList shouldHaveSize 0
            resp.messagesList.map { it.code } shouldBe listOf("access_denied", "policy_unresolvable_attribute")
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

        // LR C-5·3 — the engine used to collect tables from the plan tree only.
        "a table read only inside an EXISTS subquery gets its predicate" {
            val catalogSales = table("catalog_sales")
            val exists =
                Expression
                    .newBuilder()
                    .setSubquery(
                        org.tatrman.plan.v1.SubqueryExpression
                            .newBuilder()
                            .setKind("exists")
                            .setSubquery(scanOf(catalogSales)),
                    ).build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setFilter(
                        org.tatrman.plan.v1.FilterNode
                            .newBuilder()
                            .setInput(scanOf(table("item")))
                            .setCondition(exists),
                    ).build()
            val registry =
                PolicyRegistry(
                    listOf(
                        Policy(
                            id = "dc_cs",
                            tableMatch = TableMatcher.Exact(catalogSales),
                            predicate = PolicyPredicate.Eq("cs_warehouse_sk", PolicyValue.Literal(5, "int")),
                        ),
                    ),
                )
            val resp = PolicyEngine(registry).evaluatePolicies(request(plan, "t:alice"))
            resp.predicatesList.map { it.table.name to it.ruleId } shouldBe listOf("catalog_sales" to "dc_cs")
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
