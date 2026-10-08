// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * In-memory store of [Policy] instances. Phase 1.5 uses this with a
 * hardcoded set of fixtures (see [DefaultPolicies]). Future Section B
 * replaces the construction call with HOCON-driven loading; the registry
 * surface is stable.
 */
class PolicyRegistry(
    private val policies: List<Policy>,
) {
    /**
     * The policies that apply to [table] for a caller holding [callerRoles]: the table-match covers
     * the table AND the role gate admits the caller ([Policy.appliesTo]). The roles are required, not
     * defaulted — a call that forgot them would read as "a caller with no roles" and silently drop
     * every role-gated policy.
     */
    fun policiesFor(
        table: QualifiedName,
        callerRoles: Collection<String>,
    ): List<Policy> = policies.filter { matches(it.tableMatch, table) && it.appliesTo(callerRoles) }

    /** Column-level rules (DF-S02) from every policy that applies to [table] for this caller, with their owning policy. */
    fun columnRulesFor(
        table: QualifiedName,
        callerRoles: Collection<String>,
    ): List<Pair<Policy, ColumnRule>> = policiesFor(table, callerRoles).flatMap { p -> p.columnRules.map { p to it } }

    fun size(): Int = policies.size

    private fun matches(
        matcher: TableMatcher,
        qname: QualifiedName,
    ): Boolean =
        when (matcher) {
            is TableMatcher.All -> true
            is TableMatcher.Exact -> matcher.qname == qname
            is TableMatcher.Namespace ->
                matcher.schemaCode == qname.schemaCode && matcher.namespace == qname.namespace
        }
}

/**
 * Canonical v1.5 fixture policies. Used by Validator integration tests and as the default
 * registry contents until HOCON-driven storage lands.
 *
 * Two named lists:
 *   - [core] is the DB-only baseline (just `tenant_isolation`). Role-LESS here, unlike the shipped
 *     `policies/policies.conf`, which gates it on `tenant-scoped` (LR C-5·2): these fixtures exercise
 *     the predicate, and a gate would make every one of them restate the role.
 *   - [all] adds the [erCustomerRegionIsolation] demo policy on top — useful for ER-flow
 *     fixtures (validator pass-1 + dispatch end-to-end) and any test that wants the full set.
 *
 * Tests that don't care about the ER demo should use [core]; tests that exercise the ER
 * security path (Validator pass 1, `wrapScans` over `ScanNode(ER, ...)`) should use [all].
 */
object DefaultPolicies {
    val tenantIsolation: Policy =
        Policy(
            id = "tenant_isolation",
            tableMatch = TableMatcher.Namespace(schemaCode = SchemaCode.DB, namespace = "dbo"),
            predicate =
                PolicyPredicate.Eq(
                    column = "tenant_id",
                    value = PolicyValue.UserAttribute("tenant_id"),
                ),
            description = "Restrict rows to the calling user's tenant",
        )

    val erCustomerRegionIsolation: Policy =
        Policy(
            id = "er_customer_region_isolation",
            tableMatch = TableMatcher.Namespace(schemaCode = SchemaCode.ER, namespace = "entity"),
            predicate =
                PolicyPredicate.Eq(
                    column = "region",
                    value = PolicyValue.UserAttribute("region"),
                ),
            description = "Restrict customer entities to the caller's region",
        )

    /** DB-only production baseline. */
    val core: List<Policy> = listOf(tenantIsolation)

    /** Production baseline + ER demo fixture (for tests and dev). */
    val all: List<Policy> = core + erCustomerRegionIsolation
}
