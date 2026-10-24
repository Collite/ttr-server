// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.runner.Table
import org.tatrman.ttrp.emit.sql.RejectGuardSql
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.catalog.ValidityCatalog
import org.tatrman.ttrp.graph.model.NodeExpressions

/** Compiler nonzero validity, restricted to its portable SQL subset; no reject rows are discarded. */
internal object PreviewDivision {
    fun validate(
        expressions: List<Expression>,
        rows: Int,
        render: (Expression) -> String,
        query: (String) -> Table,
    ) {
        val divisions = linkedSetOf<FunctionCall>()
        expressions.forEach { expression ->
            // Bottom-up order fences an inner division before it can enter an outer divisor/quotient.
            NodeExpressions.rewrite(expression) {
                if (it is FunctionCall && it.function == CatalogId.DIV) divisions += it
                null
            }
        }
        if (divisions.isEmpty()) return
        val spec = requireNotNull(ValidityCatalog.rejectCapability("op.div", "numeric,numeric->numeric"))
        require(spec.supported && spec.pure)
        for (division in divisions) {
            require(division.args.size == 2)
            val denominator = division.args[1]
            val call = FunctionCall(CatalogId("internal.is_nonzero"), listOf(denominator), division.location)
            // The operand is already rendered by the compiler/translator as a private projected column.
            // Only is_nonzero is admitted here: the other emitter guards contain PostgreSQL syntax.
            val valid = requireNotNull(RejectGuardSql.render(call, arg = { "\"_au_value_0\"" }))
            val guards =
                query(
                    "SELECT \"_au_value_0\", ($valid) AS \"_au_valid\" " +
                        "FROM (${render(denominator)}) AS \"_au_divisor\"",
                )
            require(guards.rows.size == rows)
            require(guards.rows.all { it.last() == true && finite(it.first()) }) { "invalid divisor" }
            // Even a nonzero finite divisor can overflow the quotient. Check before a predicate,
            // cast or case can hide DuckDB infinity/NaN inside a seemingly valid result.
            val quotients = query(render(division))
            require(quotients.rows.size == rows)
            require(quotients.rows.all { finite(it.last()) }) { "invalid quotient" }
        }
    }

    private fun finite(value: Any?): Boolean =
        when (value) {
            is Double -> value.isFinite()
            is Float -> value.isFinite()
            else -> true
        }
}
