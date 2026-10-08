// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * Structured security policy — what `EvaluatePolicies` returns, how the Validator wraps it. A
 * policy carries a row-level [predicate] (→ a `FilterNode` over the matched tables) and optional
 * column-level [columnRules] (→ deny / mask on (matched-table, column) pairs — DF-S02). Loaded
 * from HOCON ([PolicyConfigLoader]); [DefaultPolicies] is a test fixture.
 */
data class Policy(
    val id: String,
    val tableMatch: TableMatcher,
    val predicate: PolicyPredicate,
    val description: String = "",
    /** Column-level rules (DF-S02): columns on the matched tables that are denied or masked. */
    val columnRules: List<ColumnRule> = emptyList(),
    /**
     * The role gate (LR C-5·1). Non-empty: the policy applies only to a caller holding at least one
     * of these roles. Empty: it applies to every caller — the semantics before the gate.
     */
    val roles: List<String> = emptyList(),
    /**
     * A caller holding any of these is never subject to the policy, even when it also holds a gating
     * role. The only way to exempt anyone: a policy cannot be skipped for a caller it applies to.
     */
    val exemptRoles: List<String> = emptyList(),
    /**
     * The subject gate: when set, the policy applies only to a caller whose identity carries this
     * attribute. Tenant isolation's subjects are exactly the callers with a `tenant:user` id, so it
     * names `tenant_id`: a caller with a tenant is always narrowed to it, whatever roles they hold,
     * and a caller without one is not the policy's subject. Any OTHER attribute the predicate needs
     * and the caller lacks still refuses the request (LR C-5·2).
     */
    val subjectAttribute: String? = null,
) {
    /** Whether the policy applies to [caller]. Role names match exactly. */
    fun appliesTo(caller: Caller): Boolean =
        (roles.isEmpty() || roles.any { it in caller.roles }) &&
            exemptRoles.none { it in caller.roles } &&
            (subjectAttribute == null || subjectAttribute in caller.attributes)
}

/**
 * Who is asking, as far as a policy's gates are concerned: the effective [roles] and the names of
 * the identity [attributes] the caller carries ([ResolvedIdentity.attributeNames]). Both are required
 * — a caller built without them would read as one with no roles and no attributes, and silently
 * drop every gated policy.
 */
data class Caller(
    val roles: Collection<String>,
    val attributes: Set<String>,
)

/** A column-level access rule on a policy's matched tables. */
data class ColumnRule(
    val column: String,
    val action: ColumnAction,
)

sealed interface ColumnAction {
    /** The query may not reference this column at all. */
    data object Deny : ColumnAction

    /**
     * The column is replaced in the result by [maskValue]: a literal ("***"), a caller attribute
     * (resolved per call — an unresolvable one refuses the request), or, when absent, NULL.
     */
    data class Mask(
        val maskValue: PolicyValue? = null,
    ) : ColumnAction
}

sealed interface TableMatcher {
    /** Match by exact qualified name (`schemaCode.namespace.name`). */
    data class Exact(
        val qname: QualifiedName,
    ) : TableMatcher

    /** Match every table in a `(schemaCode, namespace)` pair. */
    data class Namespace(
        val schemaCode: SchemaCode,
        val namespace: String,
    ) : TableMatcher

    /** Match every table — used for org-wide policies. */
    data object All : TableMatcher
}

/**
 * Predicate shape v1.5 supports. Small on purpose — extend as concrete
 * policy use-cases surface. Each variant maps directly to a v1 RelOp
 * Expression operator (see `org.tatrman.plan.v1.Expression`).
 */
sealed interface PolicyPredicate {
    /** `column = value` */
    data class Eq(
        val column: String,
        val value: PolicyValue,
    ) : PolicyPredicate

    /** `column IN (values...)` */
    data class In(
        val column: String,
        val values: List<PolicyValue>,
    ) : PolicyPredicate

    /** `left AND right` */
    data class And(
        val left: PolicyPredicate,
        val right: PolicyPredicate,
    ) : PolicyPredicate

    /** `left OR right` */
    data class Or(
        val left: PolicyPredicate,
        val right: PolicyPredicate,
    ) : PolicyPredicate

    /** `NOT child` */
    data class Not(
        val child: PolicyPredicate,
    ) : PolicyPredicate
}

/**
 * Value source for a policy predicate. Per-call-resolved values
 * (`UserAttribute("tenant_id")`) are looked up on the calling user's
 * identity at evaluation time; literal values are baked in.
 */
sealed interface PolicyValue {
    data class Literal(
        val value: Any?,
        val type: String,
    ) : PolicyValue

    /**
     * Reference to an attribute on the calling user. Phase 1.5 supports two
     * out-of-the-box: `user_id` (`PipelineContext.user_id`) and `tenant_id`
     * (extracted from a `<tenant>:<user>` user_id or via whois — for v1.5
     * the simple split is enough; the whois lookup lands with Section D).
     */
    data class UserAttribute(
        val attribute: String,
    ) : PolicyValue
}
