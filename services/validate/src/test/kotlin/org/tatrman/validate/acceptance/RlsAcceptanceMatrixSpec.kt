// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.acceptance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.validate.v1.ValidationOptions
import org.tatrman.validate.client.LocalPolicyClient
import org.tatrman.validate.client.StaticMetadataClient
import org.tatrman.validate.grpc.ValidateServiceImpl
import org.tatrman.validate.policy.Policy
import org.tatrman.validate.policy.PolicyEngine
import org.tatrman.validate.policy.PolicyPredicate
import org.tatrman.validate.policy.PolicyRegistry
import org.tatrman.validate.policy.PolicyValue
import org.tatrman.validate.policy.TableMatcher
import org.tatrman.validate.stages.LlmGuard
import org.tatrman.validate.stages.RuleEnforcer
import org.tatrman.validate.stages.SecurityApplier
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode

/**
 * Fork Stage 3.6 T2 — RLS acceptance matrix (mocked component test).
 *
 * The same `customers` scan is validated for several modelled bearers (identity +
 * roles arriving as they would post-fork: `user_id` + `auth_roles` on
 * [PipelineContext], populated by query-mcp's IdentityResolver from the JWT —
 * Stage 3.5). Validate runs its real in-process validator + folded policy engine
 * (no gRPC, no whois). We then apply the *injected* security predicate to a tiny
 * mocked data source so the row sets are concrete, proving:
 *
 *   - per-tenant scoping: two non-admin users see disjoint row sets (DF-S01);
 *   - admin bypass (DF-V02): `apply_security = false` + the admin role → unfiltered;
 *   - bypass refused: `apply_security = false` WITHOUT the admin role → security is
 *     forced back on (still scoped) + a Rule-6 `security_bypass_denied`.
 *
 * Live-Keycloak acceptance against the running stack is deferred to the separate
 * integration-test suite (planning-conventions §4); this pins the enforcement
 * logic deterministically.
 */
class RlsAcceptanceMatrixSpec :
    StringSpec({
        val adminRole = "query-platform-admin"

        // One policy: rows are scoped to the caller's tenant (the tenant_id user-attr
        // resolves from the user_id split, e.g. "tenant-7:alice" -> "tenant-7").
        val tenantIsolation =
            Policy(
                id = "tenant_isolation",
                tableMatch = TableMatcher.Namespace(SchemaCode.DB, "dbo"),
                predicate = PolicyPredicate.Eq("tenant_id", PolicyValue.UserAttribute("tenant_id")),
                description = "Restrict rows to the calling user's tenant",
            )

        val service =
            ValidateServiceImpl(
                securityApplier =
                    SecurityApplier(
                        LocalPolicyClient(PolicyEngine(PolicyRegistry(listOf(tenantIsolation)))),
                    ),
                ruleEnforcer = RuleEnforcer(serviceDefault = 30),
                llmGuard = LlmGuard(enabled = false),
                metadataClient = StaticMetadataClient("v-test"),
                adminRole = adminRole,
            )

        // Mocked data source: a `customers` table spanning two tenants.
        val dataset =
            listOf(
                mapOf("tenant_id" to "tenant-7", "name" to "Acme"),
                mapOf("tenant_id" to "tenant-7", "name" to "Beta"),
                mapOf("tenant_id" to "tenant-9", "name" to "Cobalt"),
                mapOf("tenant_id" to "tenant-9", "name" to "Delta"),
            )

        fun customersScan(): PlanNode =
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

        suspend fun validateAs(
            userId: String,
            roles: List<String>,
            applySecurity: Boolean,
        ): ValidateResponse =
            service.validate(
                ValidateRequest
                    .newBuilder()
                    .setPlan(customersScan())
                    .setContext(
                        PipelineContext
                            .newBuilder()
                            .setUserId(userId)
                            .addAllAuthRoles(roles)
                            .setModelVersion("v-test"),
                    ).setOptions(
                        ValidationOptions
                            .newBuilder()
                            .setApplySecurity(applySecurity)
                            .setEnforceTopN(false),
                    ).build(),
            )

        // Apply the security predicate Validate injected (if any) to the mock dataset.
        fun matches(
            cond: Expression,
            row: Map<String, String>,
        ): Boolean =
            when (cond.function.operation) {
                "and" -> cond.function.operandsList.all { matches(it, row) }
                "eq" ->
                    row[
                        cond.function.operandsList[0]
                            .columnRef.name,
                    ] ==
                        cond.function.operandsList[1]
                            .literal.stringValue
                else -> true // no recognised predicate → no restriction
            }

        fun visibleNames(resp: ValidateResponse): List<String> {
            val plan = resp.plan
            val rows =
                if (plan.hasFilter()) {
                    dataset.filter { matches(plan.filter.condition, it) }
                } else {
                    dataset // bare scan → security not applied (admin bypass)
                }
            return rows.map { it.getValue("name") }
        }

        "tenant-7 analyst sees only tenant-7 rows" {
            val resp = validateAs("tenant-7:alice", listOf("analyst"), applySecurity = true)
            resp.securityAppliedList.any { it.ruleId == "tenant_isolation" } shouldBe true
            visibleNames(resp) shouldContainExactlyInAnyOrder listOf("Acme", "Beta")
        }

        "tenant-9 analyst sees only tenant-9 rows — disjoint from tenant-7" {
            val resp = validateAs("tenant-9:bob", listOf("analyst"), applySecurity = true)
            resp.securityAppliedList.any { it.ruleId == "tenant_isolation" } shouldBe true
            visibleNames(resp) shouldContainExactlyInAnyOrder listOf("Cobalt", "Delta")
        }

        "DF-V02 — admin with apply_security=false bypasses RLS → all rows, unfiltered" {
            val resp = validateAs("tenant-7:root", listOf(adminRole), applySecurity = false)
            // No security rule applied; plan stays a bare scan.
            resp.securityAppliedList.isEmpty() shouldBe true
            resp.plan.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            visibleNames(resp) shouldContainExactlyInAnyOrder listOf("Acme", "Beta", "Cobalt", "Delta")
        }

        "LR C-5·2 — a caller whose tenant cannot be resolved is refused, not served every tenant" {
            // Before the fail-closed change the policy was skipped and this caller got all four rows.
            val resp = validateAs("alice", listOf("analyst"), applySecurity = true)
            resp.hasPlan() shouldBe false
            resp.securityAppliedList.isEmpty() shouldBe true
            resp.messagesList.first().code shouldBe "access_denied"
        }

        "bypass refused — non-admin with apply_security=false is forced back to scoped + Rule-6" {
            val resp = validateAs("tenant-7:alice", listOf("analyst"), applySecurity = false)
            resp.messagesList.any { it.code == "security_bypass_denied" } shouldBe true
            // Security was forced on despite apply_security=false: still tenant-scoped.
            resp.securityAppliedList.any { it.ruleId == "tenant_isolation" } shouldBe true
            visibleNames(resp) shouldContainExactlyInAnyOrder listOf("Acme", "Beta")
        }
    })
