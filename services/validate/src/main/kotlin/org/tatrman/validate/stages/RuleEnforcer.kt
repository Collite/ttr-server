// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.security.v1.ColumnRule
import org.tatrman.validate.v1.ValidationOptions
import org.slf4j.LoggerFactory

/**
 * RULES stage. Two responsibilities:
 *
 *   1. **Column rule enforcement (DF-V01).** Reads `column_rules` from `EvaluatePoliciesResponse`
 *      (per-table `DENY` / `MASK` rules, DF-S02). A `DENY` on a `(table, column)` pair that the
 *      plan touches produces a structured `column_denied` ERROR and short-circuits the rest of
 *      the stage (the plan is dropped — the validator returns an error response with no plan).
 *      A `MASK` rewrites every `ColumnRef` leaf inside an `Expression` (project expressions,
 *      filter/join conditions, cast inputs) for the masked column to the rule's `mask_expression`.
 *      Bare `ColumnRef` slots that aren't wrapped in an `Expression` — group-by keys, sort keys,
 *      aggregate-call args — can't be substituted with an arbitrary expression; v1 leaves them
 *      and emits a `mask_skipped_bare_column_ref` warning so the caller knows.
 *
 *      Match policy: a denied `(T, c)` fires when `T` is in the plan's TableScans AND `c` appears
 *      in the plan's column references (`ColumnRef.name`, `TableScan.output_columns[*].name`,
 *      group_keys, sort_keys, aggregate args). Without RESOLVE-stage column qualification (Phase
 *      08) we can't always disambiguate which TableScan a bare `ColumnRef` belongs to — so this
 *      errs toward denying, which is the safe v1 posture. Document any false-positive surprises
 *      as DF-V01 follow-ups.
 *
 *   2. **TopN enforcement**: cap the root limit when `options.enforce_top_n` is true. Two service
 *      numbers and one request number decide the cap:
 *        - [serviceDefault] (`validate.default-top-n`) — what a caller gets when it asks for nothing;
 *        - [serviceMax] (`validate.max-top-n`) — the ceiling a caller may ask up to;
 *        - `options.default_top_n` — what the caller asked for (0 = nothing).
 *      `cap = min(requested > 0 ? requested : serviceDefault, serviceMax)`. [serviceMax] defaults to
 *      [serviceDefault], which is the older rule exactly: a caller could ask for fewer rows than the
 *      default and never more, because the default and the ceiling were one number.
 *
 *      When the cap is what bounds the plan — a limit injected where there was none, or an existing
 *      one lowered — and the caller did not ask for exactly that many, the result carries a
 *      [TOP_N_APPLIED] WARNING naming the cap and the request. Without it a capped answer is
 *      indistinguishable from a complete one. The warning is a statement about the PLAN: whether the
 *      data actually reached the cap is only known after execution, which is where `query` decides
 *      whether to pass it on.
 */
class RuleEnforcer(
    private val serviceDefault: Int = 30,
    private val serviceMax: Int = serviceDefault,
) {
    fun enforce(
        plan: PlanNode,
        options: ValidationOptions,
        columnRules: List<ColumnRule> = emptyList(),
    ): Result {
        val messages = mutableListOf<ResponseMessage>()

        // 1. Column rule enforcement.
        val (rewritten, rejected) = applyColumnRules(plan, columnRules, messages)
        if (rejected) {
            return Result(plan = plan, messages = messages, rejected = true)
        }

        // 2. TopN.
        val withTopN =
            if (options.enforceTopN) {
                val cap = effectiveCap(options)
                val (capped, bound) = applyTopN(rewritten, cap)
                val requested = options.defaultTopN
                if (bound && (requested <= 0 || requested > cap)) {
                    messages.add(topNApplied(cap, requested))
                }
                capped
            } else {
                log.debug("TopN enforcement disabled by ValidationOptions")
                rewritten
            }
        return Result(plan = withTopN, messages = messages, rejected = false)
    }

    data class Result(
        val plan: PlanNode,
        val messages: List<ResponseMessage>,
        val rejected: Boolean,
    )

    private fun applyColumnRules(
        plan: PlanNode,
        rules: List<ColumnRule>,
        messages: MutableList<ResponseMessage>,
    ): Pair<PlanNode, Boolean> {
        if (rules.isEmpty()) return plan to false
        val tableQnames = ColumnUsage.tableQnames(plan)
        val columnNames = ColumnUsage.columnNames(plan)

        val applicable = rules.filter { it.table in tableQnames && it.column in columnNames }
        if (applicable.isEmpty()) return plan to false

        var rejected = false
        // A MASK with nothing to mask the column with withholds it like a DENY: serving it as it is
        // would be the one outcome the rule exists to prevent. (The policy engine always sends an
        // expression; this guards any other producer.)
        val withheld =
            applicable.filter {
                it.action == ColumnRule.Action.DENY || (it.action == ColumnRule.Action.MASK && !it.hasMaskExpression())
            }
        for (rule in withheld) {
            log.warn(
                "Column rule '{}' denies access to {}.{}",
                rule.ruleId,
                rule.table.dotted(),
                rule.column,
            )
            messages.add(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.ERROR)
                    .setCode("column_denied")
                    .setHumanMessage(
                        "Query references column '${rule.column}' on table '${rule.table.dotted()}', which is restricted by policy.",
                    ).build(),
            )
            rejected = true
        }
        if (rejected) return plan to true

        // Build a per-table mask lookup (column -> mask expression). Multiple masks on the same
        // (table, column) are unusual; last wins, with a warning.
        val maskLookup =
            applicable
                .filter { it.action == ColumnRule.Action.MASK }
                .groupBy { it.table to it.column }
                .mapValues { (key, group) ->
                    if (group.size > 1) {
                        log.warn(
                            "Multiple MASK rules for ${key.first.dotted()}.${key.second}; using rule '{}'",
                            group.last().ruleId,
                        )
                    }
                    group.last()
                }
        if (maskLookup.isEmpty()) return plan to false

        var bareRefSkipped = false
        val masked =
            ExpressionRewriter.rewriteColumnRefs(plan) { ref ->
                // Match by column name only (table membership is checked at the plan level via
                // `tableQnames` above — every applicable rule's table is in this plan, so a bare
                // ColumnRef matching a rule's column is conservatively assumed to be from that
                // table). When multiple tables have masks on the same column name, the v1 walker
                // can't disambiguate — both rules apply and the first match wins.
                val rule = maskLookup.values.firstOrNull { it.column == ref.name } ?: return@rewriteColumnRefs null
                rule.maskExpression
            }

        // Detect bare-ColumnRef slots that the rewriter can't touch — group_keys, sort_keys,
        // aggregate.args — and warn the caller. (Done after the rewrite since the rewriter doesn't
        // visit those slots.)
        for ((_, rule) in maskLookup) {
            if (bareColumnRefExistsFor(masked, rule.column)) {
                bareRefSkipped = true
                messages.add(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("mask_skipped_bare_column_ref")
                        .setHumanMessage(
                            "MASK rule '${rule.ruleId}' for ${rule.table.dotted()}.${rule.column} could not be applied to a group-by / sort / aggregate column reference; that occurrence is unmasked.",
                        ).build(),
                )
            }
        }
        if (bareRefSkipped) {
            log.warn(
                "MASK rewrite left at least one bare ColumnRef untouched; see mask_skipped_bare_column_ref warnings",
            )
        }

        return masked to false
    }

    private fun bareColumnRefExistsFor(
        plan: PlanNode,
        column: String,
    ): Boolean =
        when (plan.nodeCase) {
            PlanNode.NodeCase.AGGREGATE ->
                plan.aggregate.groupKeysList.any { it.name == column } ||
                    plan.aggregate.aggregatesList.any { call -> call.argsList.any { it.name == column } } ||
                    bareColumnRefExistsFor(plan.aggregate.input, column)
            PlanNode.NodeCase.SORT ->
                plan.sort.sortKeysList.any { it.column.name == column } ||
                    bareColumnRefExistsFor(plan.sort.input, column)
            PlanNode.NodeCase.PROJECT -> bareColumnRefExistsFor(plan.project.input, column)
            PlanNode.NodeCase.FILTER -> bareColumnRefExistsFor(plan.filter.input, column)
            PlanNode.NodeCase.JOIN ->
                bareColumnRefExistsFor(plan.join.left, column) || bareColumnRefExistsFor(plan.join.right, column)
            PlanNode.NodeCase.UNION ->
                plan.union.inputsList.any { bareColumnRefExistsFor(it, column) }
            PlanNode.NodeCase.LIMIT_OFFSET -> bareColumnRefExistsFor(plan.limitOffset.input, column)
            PlanNode.NodeCase.SUBQUERY -> bareColumnRefExistsFor(plan.subquery.subquery, column)
            PlanNode.NodeCase.TABLE_SCAN,
            PlanNode.NodeCase.SCAN,
            PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.VALUES,
            PlanNode.NodeCase.NODE_NOT_SET,
            // A Store is a write-plan root, never a source for ORDER-BY / group-by
            // mask resolution; no bare column ref to resolve here.
            PlanNode.NodeCase.STORE,
            -> false
        }

    private fun effectiveCap(options: ValidationOptions): Int {
        val requested = options.defaultTopN
        return minOf(if (requested > 0) requested else serviceDefault, serviceMax)
    }

    /** The plan with its root limit capped, and whether the cap was what bounded it. */
    private fun applyTopN(
        plan: PlanNode,
        cap: Int,
    ): Pair<PlanNode, Boolean> {
        if (plan.nodeCase != PlanNode.NodeCase.LIMIT_OFFSET) {
            val wrapped =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(
                        LimitOffsetNode
                            .newBuilder()
                            .setInput(plan)
                            .setLimit(cap.toLong()),
                    ).build()
            return wrapped to true
        }
        val existing = plan.limitOffset
        if (existing.hasLimit() && existing.limit <= cap.toLong()) {
            return plan to false
        }
        val rewritten =
            existing
                .toBuilder()
                .setLimit(cap.toLong())
        return PlanNode.newBuilder().setLimitOffset(rewritten).build() to true
    }

    private fun topNApplied(
        cap: Int,
        requested: Int,
    ): ResponseMessage {
        val asked =
            if (requested > 0) {
                "the caller asked for $requested"
            } else {
                "the caller stated no row limit, so the service default applies"
            }
        return ResponseMessage
            .newBuilder()
            .setSeverity(Severity.WARNING)
            .setCode(TOP_N_APPLIED)
            .setHumanMessage("Answer limited to $cap rows by the row cap; $asked.")
            .build()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RuleEnforcer::class.java)

        /** The code of the WARNING raised when the row cap bounds a plan below what was asked. */
        const val TOP_N_APPLIED = "top_n_applied"
    }
}
