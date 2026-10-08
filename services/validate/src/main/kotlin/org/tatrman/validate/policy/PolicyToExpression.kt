// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal

/**
 * Converts a [PolicyPredicate] (with [PolicyValue.UserAttribute] references
 * resolved against the calling identity) into a v1
 * [org.tatrman.plan.v1.Expression] proto. The Validator wraps the resulting
 * expression as a [org.tatrman.plan.v1.FilterNode] above the matching
 * TableScan.
 */
object PolicyToExpression {
    fun convert(
        predicate: PolicyPredicate,
        identity: ResolvedIdentity,
    ): Expression =
        when (predicate) {
            is PolicyPredicate.Eq -> eq(columnRef(predicate.column), value(predicate.value, identity))
            is PolicyPredicate.In -> inExpr(columnRef(predicate.column), predicate.values.map { value(it, identity) })
            is PolicyPredicate.And ->
                binary(
                    "and",
                    convert(predicate.left, identity),
                    convert(predicate.right, identity),
                )
            is PolicyPredicate.Or -> binary("or", convert(predicate.left, identity), convert(predicate.right, identity))
            is PolicyPredicate.Not ->
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("not")
                            .addOperands(convert(predicate.child, identity)),
                    ).setResultType("bool")
                    .build()
        }

    /** Build a literal [Expression] from a policy literal value — used for column-mask expressions (DF-S02). */
    fun literalExpression(v: PolicyValue.Literal): Expression = literal(v.value, v.type)

    private fun eq(
        left: Expression,
        right: Expression,
    ): Expression =
        Expression
            .newBuilder()
            .setFunction(
                FunctionCall
                    .newBuilder()
                    .setOperation("eq")
                    .addOperands(left)
                    .addOperands(right),
            ).setResultType("bool")
            .build()

    private fun inExpr(
        left: Expression,
        values: List<Expression>,
    ): Expression {
        // Phase 08 B4 / DF-S05 — emit a first-class `in` FunctionCall now that the wire format
        // catalogs IN. Operands are [left, value_1, value_2, ...] — matches Calcite's
        // RexCall(IN, ...) shape so RelToSqlUnparser emits proper `col IN (v1, v2, ...)` SQL
        // instead of the balanced OR-tree desugaring this used pre-B4.
        //
        // Empty IN: literal `false`, since an empty membership is by definition unsatisfiable.
        // This preserves the pre-B4 semantics for that edge case without engaging the IN
        // operator (Calcite rejects IN with zero operands).
        if (values.isEmpty()) {
            return Expression
                .newBuilder()
                .setLiteral(Literal.newBuilder().setBoolValue(false).setType("bool"))
                .setResultType("bool")
                .build()
        }
        val fn =
            FunctionCall
                .newBuilder()
                .setOperation("in")
                .addOperands(left)
        for (v in values) fn.addOperands(v)
        return Expression
            .newBuilder()
            .setFunction(fn)
            .setResultType("bool")
            .build()
    }

    private fun binary(
        op: String,
        left: Expression,
        right: Expression,
    ): Expression =
        Expression
            .newBuilder()
            .setFunction(
                FunctionCall
                    .newBuilder()
                    .setOperation(op)
                    .addOperands(left)
                    .addOperands(right),
            ).setResultType("bool")
            .build()

    private fun columnRef(name: String): Expression =
        Expression
            .newBuilder()
            .setColumnRef(
                org.tatrman.plan.v1.ColumnRef
                    .newBuilder()
                    .setName(name)
                    .setType("unknown"),
            ).setResultType("unknown")
            .build()

    private fun value(
        v: PolicyValue,
        identity: ResolvedIdentity,
    ): Expression =
        when (v) {
            is PolicyValue.Literal -> literal(v.value, v.type)
            is PolicyValue.UserAttribute -> {
                val resolved = identity.attribute(v.attribute)
                literal(resolved, "text")
            }
        }

    private fun literal(
        value: Any?,
        type: String,
    ): Expression {
        val b = Literal.newBuilder().setType(type)
        when (value) {
            null -> b.setIsNull(true)
            is String -> b.setStringValue(value)
            is Int -> b.setIntValue(value.toLong())
            is Long -> b.setIntValue(value)
            is Boolean -> b.setBoolValue(value)
            is Double -> b.setFloatValue(value)
            is Float -> b.setFloatValue(value.toDouble())
            else -> b.setStringValue(value.toString())
        }
        return Expression
            .newBuilder()
            .setLiteral(b)
            .setResultType(type)
            .build()
    }
}

/**
 * Resolved per-call identity attributes.
 *
 * [fromUserId] extracts what it can from the [PipelineContext.userId][org.tatrman.plan.v1.PipelineContext.userId]
 * alone (the `tenant:user` split convention); [withExtra] layers external attributes on top — since
 * the Stage 3.2 fold the engine layers the bearer `roles` (from `PipelineContext.auth_roles`) here.
 * The userId-derived attributes are authoritative and win on conflict so an external source can't
 * override the tenant gate.
 */
data class ResolvedIdentity(
    val userId: String,
    val attributes: Map<String, String>,
) {
    fun attribute(name: String): String =
        attributes[name]
            ?: when (name) {
                "user_id" -> userId
                else -> throw UnresolvableAttributeException(name, userId)
            }

    /** Layer whois-sourced (or other external) attributes underneath the authoritative core. */
    fun withExtra(extra: Map<String, String>): ResolvedIdentity =
        if (extra.isEmpty()) this else copy(attributes = extra + attributes)

    companion object {
        /** Minimal identity factory — splits `tenant:user` if present. */
        fun fromUserId(userId: String): ResolvedIdentity {
            val parts = userId.split(":", limit = 2)
            val attrs =
                if (parts.size == 2) {
                    mapOf("tenant_id" to parts[0], "user_id" to parts[1])
                } else {
                    mapOf("user_id" to userId)
                }
            return ResolvedIdentity(userId, attrs)
        }
    }
}

/**
 * A policy names a caller attribute the identity does not carry. The engine turns this into a
 * denial (LR C-5·2) — never into a skipped policy, which would serve the rows the policy restricts.
 */
class UnresolvableAttributeException(
    val attribute: String,
    userId: String,
) : RuntimeException("UserAttribute '$attribute' is not resolved on identity '$userId'")
