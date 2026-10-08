// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesRequest

/**
 * Fork Stage 3.2 — the bearer-roles seam. Replaces ai-platform's whois-enrichment spec:
 * roles arrive on the request context (`auth_roles`, from the bearer's `realm_access.roles`),
 * NOT from a whois lookup. A policy that gates on `UserAttribute("roles")` evaluates against
 * the forwarded roles; with no roles present it is skipped (no whois fallback).
 */
class PolicyRolesSpec :
    StringSpec({
        val rolePolicy =
            Policy(
                id = "role_gate",
                tableMatch = TableMatcher.Namespace(SchemaCode.DB, "dbo"),
                predicate = PolicyPredicate.Eq("role_tag", PolicyValue.UserAttribute("roles")),
                description = "Gate on the caller's bearer roles",
            )
        val engine = PolicyEngine(PolicyRegistry(listOf(rolePolicy)))

        fun scan(): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName("customers"),
                    ),
                ).build()

        fun req(vararg roles: String): EvaluatePoliciesRequest =
            EvaluatePoliciesRequest
                .newBuilder()
                .setPlan(scan())
                .setContext(
                    PipelineContext
                        .newBuilder()
                        .setUserId("tenant-1:alice")
                        .addAllAuthRoles(roles.toList()),
                ).build()

        "bearer roles populate the identity and drive policy evaluation (no whois)" {
            val resp = engine.evaluatePolicies(req("analyst"))
            resp.predicatesList shouldHaveSize 1
            resp.predicatesList[0].ruleId shouldBe "role_gate"
            // The UserAttribute("roles") value resolves to the forwarded bearer roles.
            resp.predicatesList[0]
                .predicate.function.operandsList[1]
                .literal.stringValue shouldBe "analyst"
        }

        "the role gate reads the request's auth_roles — a gated policy reaches holders only" {
            val gated =
                PolicyEngine(
                    PolicyRegistry(
                        listOf(
                            Policy(
                                id = "dc-scope",
                                tableMatch = TableMatcher.Namespace(SchemaCode.DB, "dbo"),
                                predicate = PolicyPredicate.Eq("warehouse_sk", PolicyValue.Literal(5, "int")),
                                roles = listOf("scope-dc-5"),
                            ),
                        ),
                    ),
                )
            gated.evaluatePolicies(req("analyst", "scope-dc-5")).predicatesList.map { it.ruleId } shouldBe
                listOf("dc-scope")
            gated.evaluatePolicies(req("analyst")).predicatesList shouldHaveSize 0
            gated.evaluatePolicies(req("analyst")).messagesList shouldHaveSize 0
        }

        "missing bearer roles → a policy keyed on the roles attribute denies, no whois fallback" {
            // LR C-5·2: this used to skip the policy and serve the rows unrestricted.
            val resp = engine.evaluatePolicies(req())
            resp.predicatesList shouldHaveSize 0
            resp.messagesList.map { it.code } shouldBe listOf("access_denied", "policy_unresolvable_attribute")
        }
    })
