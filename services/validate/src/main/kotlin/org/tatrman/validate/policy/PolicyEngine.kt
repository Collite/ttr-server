// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.security.v1.ColumnRule as ProtoColumnRule
import org.tatrman.security.v1.EvaluatePoliciesRequest
import org.tatrman.security.v1.EvaluatePoliciesResponse
import org.tatrman.security.v1.TablePredicate
import org.slf4j.LoggerFactory

/**
 * PolicyEngine — the in-process row-level-security + column-rule evaluator folded from
 * ai-platform's `infra/sql-security` `SecurityServiceImpl.evaluatePolicies` (fork Stage 3.2).
 *
 * The fold drops the gRPC/REST service layers, the legacy SQL-fragment endpoint, OPA, and the
 * **whois role lookup**. Roles now arrive on the bearer: `EvaluatePoliciesRequest.context.auth_roles`
 * (populated upstream at the query-mcp edge from the JWT's `realm_access.roles`). This is the
 * fork-default `roleSource = bearer` (contracts §3); the optional whois enrichment source is a
 * Phase-5 additive, not built here.
 *
 * Policies come from HOCON (DF-S01 — [PolicyConfigLoader]). Metadata-aware checks (DF-S02): the
 * engine consults the [PolicyMetadataClient] to flag tables absent from the model (`unknown_table`)
 * and a stale request `model_version` (`model_version_mismatch`). Column rules (DF-S02) are returned
 * in `EvaluatePoliciesResponse.column_rules` for Validate's RuleEnforcer (DF-V01).
 */
class PolicyEngine(
    private val registry: PolicyRegistry,
    private val metadata: PolicyMetadataClient = StaticPolicyMetadataClient.permissive(),
) {
    suspend fun evaluatePolicies(request: EvaluatePoliciesRequest): EvaluatePoliciesResponse {
        val context = request.context
        val tableQnames = collectTableScans(request.plan)

        val response = EvaluatePoliciesResponse.newBuilder().setContext(context)
        val identity = resolveIdentity(context.userId, context.authRolesList)

        // Metadata-aware: flag a stale plan (request model_version ≠ live model version).
        if (context.modelVersion.isNotEmpty()) {
            val live = metadata.currentVersion()
            if (live.isNotEmpty() && live != context.modelVersion) {
                response.addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("model_version_mismatch")
                        .setHumanMessage(
                            "Request model_version '${context.modelVersion}' ≠ live metadata version '$live' — policies evaluated against the request's tables anyway",
                        ),
                )
            }
        }

        for (table in tableQnames) {
            // Metadata-aware: flag a table the model doesn't know (typo / dropped table).
            if (!metadata.objectExists(table)) {
                response.addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("unknown_table")
                        .setHumanMessage(
                            "Table '${table.schemaCode}.${table.namespace}.${table.name}' is not present in the metadata model",
                        ),
                )
            }
            val applicable = registry.policiesFor(table, context.authRolesList)
            for (policy in applicable) {
                try {
                    val expr = PolicyToExpression.convert(policy.predicate, identity)
                    response.addPredicates(
                        TablePredicate
                            .newBuilder()
                            .setTable(table)
                            .setPredicate(expr)
                            .setRuleId(policy.id)
                            .setPredicateSummary(policy.description.ifEmpty { policy.id }),
                    )
                } catch (ex: Exception) {
                    log.warn("Skipping policy '{}' on table '{}': {}", policy.id, table.name, ex.message)
                    response.addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("policy_evaluation_skipped")
                            .setHumanMessage("Policy '${policy.id}' on '${table.name}' skipped: ${ex.message}"),
                    )
                }
            }
            // Column-level rules for this table (DF-S02) — Validate's RuleEnforcer checks the actual
            // query's columns against these (deny → reject; mask → rewrite).
            for ((policy, rule) in registry.columnRulesFor(table, context.authRolesList)) {
                val b =
                    ProtoColumnRule
                        .newBuilder()
                        .setTable(table)
                        .setColumn(rule.column)
                        .setRuleId(policy.id)
                when (val action = rule.action) {
                    is ColumnAction.Deny -> b.action = ProtoColumnRule.Action.DENY
                    is ColumnAction.Mask -> {
                        b.action = ProtoColumnRule.Action.MASK
                        (action.maskValue as? PolicyValue.Literal)?.let {
                            b.maskExpression = PolicyToExpression.literalExpression(it)
                        }
                    }
                }
                response.addColumnRules(b)
            }
        }
        return response.build()
    }

    /** Loaded-policy count, for /status surfaces. */
    fun loadedPolicies(): Int = registry.size()

    /**
     * Build the per-call identity from the bearer. `tenant_id`/`user_id` come from the
     * `tenant:user` split of `user_id` (authoritative); `roles` is the forwarded
     * `auth_roles` list (the bearer's `realm_access.roles`). No whois hop.
     */
    private fun resolveIdentity(
        userId: String,
        authRoles: List<String>,
    ): ResolvedIdentity {
        val base = ResolvedIdentity.fromUserId(userId)
        return if (authRoles.isEmpty()) {
            base
        } else {
            base.withExtra(mapOf("roles" to authRoles.joinToString(",")))
        }
    }

    private fun collectTableScans(
        plan: PlanNode,
        acc: MutableSet<QualifiedName> = mutableSetOf(),
    ): Set<QualifiedName> {
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> acc.add(plan.tableScan.table)
            PlanNode.NodeCase.SCAN -> acc.add(plan.scan.getObject())
            PlanNode.NodeCase.PROJECT -> collectTableScans(plan.project.input, acc)
            PlanNode.NodeCase.FILTER -> collectTableScans(plan.filter.input, acc)
            PlanNode.NodeCase.JOIN -> {
                collectTableScans(plan.join.left, acc)
                collectTableScans(plan.join.right, acc)
            }
            PlanNode.NodeCase.UNION -> plan.union.inputsList.forEach { collectTableScans(it, acc) }
            PlanNode.NodeCase.AGGREGATE -> collectTableScans(plan.aggregate.input, acc)
            PlanNode.NodeCase.SORT -> collectTableScans(plan.sort.input, acc)
            PlanNode.NodeCase.LIMIT_OFFSET -> collectTableScans(plan.limitOffset.input, acc)
            PlanNode.NodeCase.SUBQUERY -> collectTableScans(plan.subquery.subquery, acc)
            // Store (write-plan root): RLS read-predicates apply to the tables scanned
            // by the `input` subtree that produces the rows to write; the write `target`
            // is not a scanned source, so recurse into `input` only.
            PlanNode.NodeCase.STORE -> collectTableScans(plan.store.input, acc)
            // workspace_ref is a session-scoped leaf with no table to evaluate against.
            PlanNode.NodeCase.WORKSPACE_REF -> Unit
            PlanNode.NodeCase.VALUES, PlanNode.NodeCase.NODE_NOT_SET -> Unit
        }
        return acc
    }

    companion object {
        private val log = LoggerFactory.getLogger(PolicyEngine::class.java)
    }
}
