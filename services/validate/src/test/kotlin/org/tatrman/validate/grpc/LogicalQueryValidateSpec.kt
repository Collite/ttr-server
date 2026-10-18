// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.HartlandStackModel
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.validate.client.LocalPolicyClient
import org.tatrman.validate.client.StaticMetadataClient
import org.tatrman.validate.policy.ColumnAction
import org.tatrman.validate.policy.ColumnRule
import org.tatrman.validate.policy.Policy
import org.tatrman.validate.policy.PolicyEngine
import org.tatrman.validate.policy.PolicyPredicate
import org.tatrman.validate.policy.PolicyRegistry
import org.tatrman.validate.policy.PolicyValue
import org.tatrman.validate.policy.TableMatcher
import org.tatrman.validate.stages.LlmGuard
import org.tatrman.validate.stages.RuleEnforcer
import org.tatrman.validate.stages.SecurityApplier
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidationOptions

/**
 * LQ-T2·S2.4 (contracts L-9, L-13, L-14) — the whole Validate call over the ER plan the query service's first call
 * produces with EXPAND_LOGICAL_BODIES: an entity stacked on a logical query expanded under a stamped boundary.
 */
class LogicalQueryValidateSpec :
    StringSpec({
        val model = HartlandStackModel.stacked()
        val sale = HartlandJoinModel.er("sale")
        val stacked = HartlandStackModel.shopperRevenue.qname

        fun expanded(sql: String): PlanNode =
            Translator(model)
                .parseToRelNode(
                    sql,
                    Language.SQL,
                    targetSchema = SchemaCode.ER,
                    sourceSchema = SchemaCode.ER,
                    expandLogicalBodies = true,
                ).shouldBeInstanceOf<ParseResult.Success>()
                .plan

        fun policy(
            id: String,
            entity: org.tatrman.plan.v1.QualifiedName,
            column: String,
        ) = Policy(
            id = id,
            tableMatch = TableMatcher.Exact(entity),
            predicate = PolicyPredicate.Eq(column, PolicyValue.Literal(5, "int")),
        )

        val service =
            ValidateServiceImpl(
                securityApplier =
                    SecurityApplier(
                        LocalPolicyClient(
                            PolicyEngine(
                                PolicyRegistry(
                                    listOf(
                                        policy("p_sale", sale, "store_id"),
                                        policy("p_stacked", stacked, "shopper_id"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ruleEnforcer = RuleEnforcer(serviceDefault = 30),
                llmGuard = LlmGuard(enabled = false),
                metadataClient = StaticMetadataClient("v-test"),
            )

        fun serviceWith(vararg policies: Policy) =
            ValidateServiceImpl(
                securityApplier = SecurityApplier(LocalPolicyClient(PolicyEngine(PolicyRegistry(policies.toList())))),
                ruleEnforcer = RuleEnforcer(serviceDefault = 30),
                llmGuard = LlmGuard(enabled = false),
                metadataClient = StaticMetadataClient("v-test"),
            )

        suspend fun ValidateServiceImpl.check(plan: PlanNode) =
            validate(
                ValidateRequest
                    .newBuilder()
                    .setPlan(plan)
                    .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                    .setOptions(ValidationOptions.newBuilder().setApplySecurity(true).setEnforceTopN(false))
                    .build(),
            )

        fun unexpanded(sql: String): PlanNode =
            Translator(model)
                .parseToRelNode(sql, Language.SQL, targetSchema = SchemaCode.ER, sourceSchema = SchemaCode.ER)
                .shouldBeInstanceOf<ParseResult.Success>()
                .plan

        /** Pass 1's plan re-entered to DB and unparsed — what would run. */
        fun sqlOf(secured: PlanNode): String {
            val db =
                Translator(model)
                    .parseToRelNode(
                        String(secured.toByteArray(), Charsets.ISO_8859_1),
                        Language.REL_NODE,
                        targetSchema = SchemaCode.DB,
                    ).shouldBeInstanceOf<ParseResult.Success>()
                    .plan
            return Translator(model)
                .unparseFromRelNode(db, Language.SQL, SqlDialect.MSSQL)
                .shouldBeInstanceOf<UnparseResult.Success>()
                .output
        }

        fun withRules(
            id: String,
            entity: org.tatrman.plan.v1.QualifiedName,
            column: String,
            vararg rules: ColumnRule,
        ) = policy(id, entity, column).copy(columnRules = rules.toList())

        val stackedQuery = "SELECT shopper_revenue.shopper_id, shopper_revenue.total_revenue FROM shopper_revenue"
        val namesAndRevenue = "SELECT shopper_revenue.last_name, shopper_revenue.total_revenue FROM shopper_revenue"

        // Column rules read the plan as they read it with the option off: a stacked entity's boundary stands where
        // its scan stood — the attributes it exposes are its columns — and the body under it is not theirs to read
        // or rewrite. Row policies reach into the body (L-13); column rules do not.

        "a MASK on a stacked attribute does not rewrite the row policy's view of it: the filter still filters" {
            val mask = ColumnRule("shopper_id", ColumnAction.Mask(PolicyValue.Literal(5, "int")))
            val p = withRules("p_stacked", stacked, "shopper_id", mask)
            val on = serviceWith(p).check(expanded(stackedQuery))
            val off = serviceWith(p).check(unexpanded(stackedQuery))
            on.securityAppliedList.map { it.ruleId } shouldBe listOf("p_stacked")
            sqlOf(on.plan) shouldContain "= 5"
            sqlOf(on.plan) shouldBe sqlOf(off.plan)
        }

        "a MASK on an inner entity's column leaves the expanded body alone (its join condition included)" {
            val p =
                withRules(
                    "p_mask",
                    HartlandJoinModel.er("shopper"),
                    "shopper_id",
                    ColumnRule("shopper_id", ColumnAction.Mask(null)),
                )
            val on = serviceWith(p).check(expanded(namesAndRevenue))
            on.messagesList.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            on.messagesList.none { it.code == "mask_skipped_bare_column_ref" } shouldBe true
            // the inner row policy is placed (L-13); the body's join still joins on shopper_id
            on.securityAppliedList.map { it.ruleId } shouldBe listOf("p_mask")
            val sql = sqlOf(on.plan)
            sql.contains("1 = 0") shouldBe false
            sql shouldContain "= 5"
        }

        "a DENY on an inner entity's column does not refuse a query over the stacked entity (as with the option off)" {
            val p = withRules("p_deny", sale, "store_id", ColumnRule("store_id", ColumnAction.Deny))
            val q = "SELECT shopper_revenue.last_name FROM shopper_revenue"
            serviceWith(p).check(unexpanded(q)).messagesList.none { it.code == "column_denied" } shouldBe true
            serviceWith(p).check(expanded(q)).messagesList.none { it.code == "column_denied" } shouldBe true
        }

        "a DENY on a stacked attribute still refuses a query that reads it" {
            val p = withRules("p_deny", stacked, "shopper_id", ColumnRule("total_revenue", ColumnAction.Deny))
            serviceWith(p).check(expanded(stackedQuery)).messagesList.any { it.code == "column_denied" } shouldBe true
        }

        suspend fun validate(plan: PlanNode) =
            service.validate(
                ValidateRequest
                    .newBuilder()
                    .setPlan(plan)
                    .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                    .setOptions(ValidationOptions.newBuilder().setApplySecurity(true).setEnforceTopN(false))
                    .build(),
            )

        "L-9 — a stamped boundary over a pure-ER body passes the mixed-layer check; both policies are placed" {
            val plan = expanded("SELECT shopper_revenue.last_name, shopper_revenue.total_revenue FROM shopper_revenue")
            val resp = validate(plan)
            resp.messagesList.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            resp.messagesList.none { it.code == "validator_mixed_layer_tree" } shouldBe true
            resp.hasPlan() shouldBe true
            resp.securityAppliedList.map { it.ruleId to it.table }.toSet() shouldBe
                setOf("p_sale" to "er.entity.sale", "p_stacked" to "er.entity.shopper_revenue")
            // the boundary policy sits directly above the stamped Project
            resp.plan.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            val wrapped =
                generateSequence(resp.plan) { n ->
                    if (n.hasProject() &&
                        !n.project.hasExpandedFrom()
                    ) {
                        n.project.input
                    } else {
                        null
                    }
                }.last()
            wrapped.hasFilter() shouldBe true
            wrapped.filter.input.project.expandedFrom shouldBe stacked
            wrapped.filter.condition.function.operandsList[1]
                .literal.intValue shouldBe 5
        }

        "the workspace rule is unaffected: a stacked entity is not a workspace" {
            val resp = validate(expanded("SELECT shopper_revenue.last_name FROM shopper_revenue"))
            resp.context.warningsList.none { it.code == "security_skipped_for_workspace" } shouldBe true
            resp.securityAppliedList.isEmpty() shouldBe false
        }

        "used_objects names the stacked entity and the entities its body reads" {
            val resp = validate(expanded("SELECT shopper_revenue.last_name FROM shopper_revenue"))
            resp.context.usedObjectsList.map { it.kind to it.qualifiedName } shouldContainAll
                listOf(
                    "entity" to "er.entity.shopper_revenue",
                    "entity" to "er.entity.sale",
                    "entity" to "er.entity.shopper",
                )
        }
    })
