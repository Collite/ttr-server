// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.Warning
import org.tatrman.security.v1.ColumnRule
import org.tatrman.security.v1.EvaluatePoliciesRequest
import org.tatrman.validate.v1.SecurityRuleApplied
import org.slf4j.LoggerFactory
import org.tatrman.validate.client.SecurityClient
import org.tatrman.plan.v1.schemaCodeToToken

/**
 * SECURITY stage. Calls sql-security `EvaluatePolicies`, AND-merges multiple
 * predicates targeting the same physical table, and wraps each matching
 * TableScan in the input plan with a FilterNode whose condition is the merged
 * expression. Returns the augmented plan plus an audit list and any
 * sql-security-side warnings (so the gRPC surface can append them to the
 * outgoing context).
 */
class SecurityApplier(
    private val client: SecurityClient,
) {
    suspend fun apply(
        plan: PlanNode,
        context: PipelineContext,
    ): Result {
        val response =
            client.evaluatePolicies(
                EvaluatePoliciesRequest
                    .newBuilder()
                    .setPlan(plan)
                    .setContext(context)
                    .build(),
            )

        // LR C-5·2 — an ERROR from the engine is a denial. Nothing is wrapped and nothing is reported
        // applied: the caller gets a refusal, never a plan restricted by only some of its policies.
        if (response.messagesList.any { it.severity == Severity.ERROR }) {
            return Result(plan = plan, applied = emptyList(), messages = response.messagesList.toList(), denied = true)
        }

        val byTable = response.predicatesList.groupBy { it.table }
        var augmented = plan
        val applied = mutableListOf<SecurityRuleApplied>()
        val warnings = mutableListOf<Warning>()

        for ((table, group) in byTable) {
            val merged = AndPredicates.merge(group.map { it.predicate })
            augmented = PlanWalker.wrapScans(augmented, target = table, predicate = merged) { qname -> qname == table }
            for (entry in group) {
                applied.add(
                    SecurityRuleApplied
                        .newBuilder()
                        .setRuleId(entry.ruleId)
                        .setPredicateSummary(entry.predicateSummary)
                        .build(),
                )
                // DF-V05 / G7 — `security_predicate_applied` pipeline-warning per (table, rule)
                // so downstream consumers (query-mcp -> agents) can surface "extra filters were
                // applied to your query because of policy X on table T" without re-deriving it
                // from `security_applied`. The predicate body is NOT included — leak-safe.
                warnings.add(
                    Warning
                        .newBuilder()
                        .setCode("security_predicate_applied")
                        .setMessage(
                            "Security rule '${entry.ruleId}' applied to '${qnameDot(table)}'.",
                        ).setSourceStage("security")
                        .setSourceService("validator")
                        .build(),
                )
                log.debug("Wrapped table {} with rule {}", table.name, entry.ruleId)
            }
        }
        return Result(
            plan = augmented,
            applied = applied,
            messages = response.messagesList.toList(),
            columnRules = response.columnRulesList.toList(),
            warnings = warnings,
        )
    }

    private fun qnameDot(qn: org.tatrman.plan.v1.QualifiedName): String =
        buildString {
            append(schemaCodeToToken(qn.schemaCode))
            append('.')
            append(qn.namespace)
            append('.')
            append(qn.name)
        }

    data class Result(
        val plan: PlanNode,
        val applied: List<SecurityRuleApplied>,
        val messages: List<ResponseMessage>,
        val columnRules: List<ColumnRule> = emptyList(),
        val warnings: List<Warning> = emptyList(),
        /** The policy engine refused the request; [plan] is the input, untouched, and must not run. */
        val denied: Boolean = false,
    )

    companion object {
        private val log = LoggerFactory.getLogger(SecurityApplier::class.java)
    }
}
