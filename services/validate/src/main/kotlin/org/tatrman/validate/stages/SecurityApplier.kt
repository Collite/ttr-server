// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.Warning
import org.tatrman.security.v1.ColumnRule
import org.tatrman.security.v1.EvaluatePoliciesRequest
import org.tatrman.security.v1.TablePredicate
import org.tatrman.validate.v1.SecurityRuleApplied
import org.slf4j.LoggerFactory
import org.tatrman.validate.client.SecurityClient

/**
 * SECURITY stage. Calls the policy engine's `EvaluatePolicies` ([evaluate]), AND-merges multiple
 * predicates targeting the same table, and wraps each matching scan in the plan with a FilterNode
 * whose condition is the merged expression ([Decision.Allowed.wrap]). The two steps are apart so
 * the Validator can enforce the column rules on the plan AS ASKED, before any security filter is in
 * it: a mask on a policy's own column must not rewrite the policy's filter, and a column the caller
 * never named must not be denied because the filter names it.
 */
class SecurityApplier(
    private val client: SecurityClient,
) {
    /** The engine's decision for [plan] and this caller: refused, or allowed under its predicates and column rules. */
    suspend fun evaluate(
        plan: PlanNode,
        context: PipelineContext,
    ): Decision {
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
            return Denied(response.messagesList.toList())
        }
        return Decision.Allowed(
            predicates = response.predicatesList.toList(),
            columnRules = response.columnRulesList.toList(),
            messages = response.messagesList.toList(),
        )
    }

    /** [evaluate] and [Decision.Allowed.wrap] in one step, for a caller with no column rules to place between them. */
    suspend fun apply(
        plan: PlanNode,
        context: PipelineContext,
    ): Result =
        when (val decision = evaluate(plan, context)) {
            is Denied -> decision
            is Decision.Allowed -> decision.wrap(plan)
        }

    /** What [evaluate] decided. */
    sealed interface Decision {
        val messages: List<ResponseMessage>

        /**
         * The request may run under [predicates] (placed by [wrap]) and [columnRules] (enforced by the
         * RuleEnforcer). [messages] are the engine's warnings.
         */
        class Allowed(
            private val predicates: List<TablePredicate>,
            val columnRules: List<ColumnRule>,
            override val messages: List<ResponseMessage>,
        ) : Decision {
            /**
             * [plan] with every restricted scan under its merged predicate — one pass for all tables.
             * The receipt names only tables [plan] reads: a rule is reported applied where it was placed.
             */
            fun wrap(plan: PlanNode): Applied {
                val read = PlanWalker.scannedTables(plan)
                val byTable = predicates.filter { it.table in read }.groupBy { it.table }
                val merged = byTable.mapValues { (_, group) -> AndPredicates.merge(group.map { it.predicate }) }
                val applied = mutableListOf<SecurityRuleApplied>()
                val warnings = mutableListOf<Warning>()
                for ((table, group) in byTable) {
                    for (entry in group) {
                        applied.add(
                            SecurityRuleApplied
                                .newBuilder()
                                .setRuleId(entry.ruleId)
                                .setPredicateSummary(entry.predicateSummary)
                                // LR C-5·5 — which table this rule narrowed. Every entry IS a row
                                // restriction of its table: a reader keys "your result may be
                                // incomplete" on the entry being there.
                                .setTable(table.dotted())
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
                                .setMessage("Security rule '${entry.ruleId}' applied to '${table.dotted()}'.")
                                .setSourceStage("security")
                                .setSourceService("validator")
                                .build(),
                        )
                        log.debug("Wrapped table {} with rule {}", table.name, entry.ruleId)
                    }
                }
                return Applied(
                    plan = PlanWalker.wrapScans(plan) { merged[it] },
                    applied = applied,
                    messages = messages,
                    columnRules = columnRules,
                    warnings = warnings,
                )
            }
        }
    }

    /** What [apply] returns. A [Denied] result has no plan, so nothing can run one by mistake. */
    sealed interface Result {
        val messages: List<ResponseMessage>
    }

    /** The plan with every applicable predicate placed. */
    data class Applied(
        val plan: PlanNode,
        val applied: List<SecurityRuleApplied>,
        override val messages: List<ResponseMessage>,
        val columnRules: List<ColumnRule> = emptyList(),
        val warnings: List<Warning> = emptyList(),
    ) : Result

    /** The policy engine refused the request; [messages] say why (`access_denied` first). */
    data class Denied(
        override val messages: List<ResponseMessage>,
    ) : Result,
        Decision

    companion object {
        private val log = LoggerFactory.getLogger(SecurityApplier::class.java)
    }
}
