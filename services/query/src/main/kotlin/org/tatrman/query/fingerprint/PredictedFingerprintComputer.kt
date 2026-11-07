// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.fingerprint

import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import java.security.MessageDigest

/**
 * Computes a predicted Arrow IPC schema fingerprint from a post-Translator
 * `PlanNode`. Query returns this on `Compile` and uses it to
 * cross-check the actual fingerprint that arrives on the first
 * `ResultBatch` from the Worker.
 *
 * Round 7 §D and Bora's answer to question 7.A.6:
 *   - mismatch logs at WARN, never fails the call (the cost is just a
 *     less-effective client cache)
 *
 * The Worker computes the actual fingerprint as
 *   `SHA-256(canonical Arrow IPC schema bytes)`
 * (see workers/mssql/.../arrow/ArrowIpcSerializer). The Worker has the
 * full ResultSetMetaData with physical types; the runner only has the
 * post-Translator PlanNode whose output column types come from the model.
 *
 * v1 implementation: walk the plan's outermost projecting node, collect
 * `(name, type)` pairs, and SHA-256 a canonical string form
 * (`name1:type1|name2:type2|...`). This MAY differ from the Worker's true
 * Arrow-bytes fingerprint — that's why mismatch is a warning, not an error.
 * v1.5+ refines the predictor to actually serialise an Arrow schema with
 * the same bytes the Worker produces.
 */
object PredictedFingerprintComputer {
    fun compute(plan: PlanNode): String {
        val columns = topLevelColumns(plan)
        val canonical =
            columns.joinToString("|") { (n, t) ->
                "$n:${t.ifEmpty { "?" }}"
            }
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the (name, type) pairs the plan's outermost projecting node
     * advertises. Walks into LimitOffset/Sort/Filter wrappers since those
     * preserve schema. Falls back to the empty list when the plan has no
     * projecting layer (e.g. a raw TableScan); callers should treat the
     * resulting fingerprint as best-effort.
     */
    private fun topLevelColumns(plan: PlanNode): List<Pair<String, String>> =
        when (plan.nodeCase) {
            PlanNode.NodeCase.PROJECT -> projectColumns(plan.project)
            PlanNode.NodeCase.AGGREGATE -> aggregateColumns(plan.aggregate)
            PlanNode.NodeCase.SORT -> topLevelColumns(plan.sort.input)
            PlanNode.NodeCase.LIMIT_OFFSET -> topLevelColumns(plan.limitOffset.input)
            PlanNode.NodeCase.FILTER -> topLevelColumns(plan.filter.input)
            PlanNode.NodeCase.SUBQUERY -> topLevelColumns(plan.subquery.subquery)
            PlanNode.NodeCase.TABLE_SCAN ->
                plan.tableScan.outputColumnsList.map { it.name to it.type }
            PlanNode.NodeCase.SCAN ->
                plan.scan.outputColumnsList.map { it.name to it.type }
            // A Union takes its output schema from the first branch (all branches
            // share the row type in SQL set-op semantics).
            PlanNode.NodeCase.UNION ->
                plan.union.inputsList
                    .firstOrNull()
                    ?.let { topLevelColumns(it) } ?: emptyList()
            PlanNode.NodeCase.JOIN, PlanNode.NodeCase.VALUES, PlanNode.NodeCase.NODE_NOT_SET,
            PlanNode.NodeCase.ROW_WRITES,
            -> emptyList()
            // Phase 2.4 — workspace_ref is a session-scoped leaf whose schema
            // is only knowable at the Worker. Best-effort fingerprint falls
            // back to empty; the Worker emits its own schema fingerprint on
            // the result batch.
            PlanNode.NodeCase.WORKSPACE_REF -> emptyList()
            // A Store node is a write-plan root; it produces no read result set,
            // so the predicted result-schema fingerprint is best-effort empty.
            PlanNode.NodeCase.STORE -> emptyList()
        }

    private fun projectColumns(node: ProjectNode): List<Pair<String, String>> =
        node.expressionsList.map { expr ->
            val name = displayName(expr)
            val type = expr.expression.resultType
            name to type
        }

    private fun aggregateColumns(node: AggregateNode): List<Pair<String, String>> {
        val keys = node.groupKeysList.map { it.aliasOr() to it.type }
        val aggs = node.aggregatesList.map { it.alias to "" }
        return keys + aggs
    }

    private fun displayName(expr: NamedExpression): String {
        if (expr.alias.isNotEmpty()) return expr.alias
        val inner = expr.expression
        if (inner.hasColumnRef()) return inner.columnRef.aliasOr()
        return ""
    }

    private fun ColumnRef.aliasOr(): String = if (alias.isNotEmpty()) alias else name
}
