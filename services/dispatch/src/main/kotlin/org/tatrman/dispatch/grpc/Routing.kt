// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.grpc

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * PlanNode walker that collects every TableScan qname. Used by the routing
 * algorithm to derive the candidate connection_id set.
 *
 * WorkspaceRef is a leaf — it represents a pre-resolved workspace-scoped
 * table that needs no routing decision (the workspace is already resolved).
 */
internal object PlanScanCollector {
    fun collect(plan: PlanNode): Set<QualifiedName> {
        val acc = LinkedHashSet<QualifiedName>()
        walk(plan, acc)
        return acc
    }

    private fun walk(
        plan: PlanNode,
        acc: MutableSet<QualifiedName>,
    ) {
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> acc.add(plan.tableScan.table)
            PlanNode.NodeCase.SCAN -> acc.add(plan.scan.getObject())
            PlanNode.NodeCase.PROJECT -> walk(plan.project.input, acc)
            PlanNode.NodeCase.FILTER -> walk(plan.filter.input, acc)
            PlanNode.NodeCase.JOIN -> {
                walk(plan.join.left, acc)
                walk(plan.join.right, acc)
            }
            PlanNode.NodeCase.UNION -> plan.union.inputsList.forEach { walk(it, acc) }
            PlanNode.NodeCase.AGGREGATE -> walk(plan.aggregate.input, acc)
            PlanNode.NodeCase.SORT -> walk(plan.sort.input, acc)
            PlanNode.NodeCase.LIMIT_OFFSET -> walk(plan.limitOffset.input, acc)
            PlanNode.NodeCase.SUBQUERY -> walk(plan.subquery.subquery, acc)
            // A Store is a write-plan root: the write `target` needs a connection
            // just like a scanned table, and its `input` read subtree carries the
            // source scans. Both contribute candidate connections.
            PlanNode.NodeCase.STORE -> {
                acc.add(plan.store.target)
                walk(plan.store.input, acc)
            }
            PlanNode.NodeCase.WORKSPACE_REF -> Unit
            PlanNode.NodeCase.VALUES, PlanNode.NodeCase.NODE_NOT_SET, PlanNode.NodeCase.ROW_WRITES -> Unit
        }
    }
}

/**
 * Issue #57 Phase C — rewrite the namespace of every DB-schema-coded TableScan/Scan qname.
 *
 * The dispatcher consumes a worker's advertised `ConnectionInfo.default_schema` here, so the
 * SQL the worker eventually emits via the translator targets the engine's actual schema
 * instead of whatever the logical plan happens to carry.
 *
 * Semantics:
 *  - Empty namespace + non-empty `defaultSchema` → fill in `defaultSchema` (the plan was
 *    schema-agnostic; concretize against the worker's default).
 *  - Non-empty namespace == `defaultSchema` → no-op.
 *  - Non-empty namespace != `defaultSchema` → no rewrite; the caller named a schema
 *    explicitly. The dispatcher records a `schema_mismatch` finding so callers can decide.
 *
 * `defaultSchema = ""` (legacy / not advertised) disables the rewrite entirely — the plan
 * passes through unchanged.
 */
internal object PlanQnameRewriter {
    data class Result(
        val plan: PlanNode,
        val mismatches: List<Mismatch>,
    )

    data class Mismatch(
        val table: QualifiedName,
        val planNamespace: String,
        val workerDefaultSchema: String,
    )

    fun applyDefaultSchema(
        plan: PlanNode,
        defaultSchema: String,
    ): Result {
        if (defaultSchema.isEmpty()) return Result(plan, emptyList())
        val mismatches = mutableListOf<Mismatch>()
        val rewritten = rewrite(plan, defaultSchema, mismatches)
        return Result(rewritten, mismatches)
    }

    private fun rewrite(
        plan: PlanNode,
        defaultSchema: String,
        mismatches: MutableList<Mismatch>,
    ): PlanNode {
        val b = plan.toBuilder()
        return when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> {
                val newQname = concretize(plan.tableScan.table, defaultSchema, mismatches) ?: return plan
                b.tableScan =
                    plan.tableScan
                        .toBuilder()
                        .setTable(newQname)
                        .build()
                b.build()
            }
            PlanNode.NodeCase.SCAN -> {
                val newQname = concretize(plan.scan.getObject(), defaultSchema, mismatches) ?: return plan
                b.scan =
                    plan.scan
                        .toBuilder()
                        .setObject(newQname)
                        .build()
                b.build()
            }
            PlanNode.NodeCase.PROJECT ->
                b
                    .setProject(
                        plan.project.toBuilder().setInput(rewrite(plan.project.input, defaultSchema, mismatches)),
                    ).build()
            PlanNode.NodeCase.FILTER ->
                b
                    .setFilter(
                        plan.filter.toBuilder().setInput(rewrite(plan.filter.input, defaultSchema, mismatches)),
                    ).build()
            PlanNode.NodeCase.JOIN ->
                b
                    .setJoin(
                        plan.join
                            .toBuilder()
                            .setLeft(rewrite(plan.join.left, defaultSchema, mismatches))
                            .setRight(rewrite(plan.join.right, defaultSchema, mismatches)),
                    ).build()
            PlanNode.NodeCase.UNION ->
                b
                    .setUnion(
                        plan.union
                            .toBuilder()
                            .clearInputs()
                            .addAllInputs(plan.union.inputsList.map { rewrite(it, defaultSchema, mismatches) }),
                    ).build()
            PlanNode.NodeCase.AGGREGATE ->
                b
                    .setAggregate(
                        plan.aggregate.toBuilder().setInput(rewrite(plan.aggregate.input, defaultSchema, mismatches)),
                    ).build()
            PlanNode.NodeCase.SORT ->
                b.setSort(plan.sort.toBuilder().setInput(rewrite(plan.sort.input, defaultSchema, mismatches))).build()
            PlanNode.NodeCase.LIMIT_OFFSET ->
                b
                    .setLimitOffset(
                        plan.limitOffset.toBuilder().setInput(
                            rewrite(plan.limitOffset.input, defaultSchema, mismatches),
                        ),
                    ).build()
            PlanNode.NodeCase.SUBQUERY ->
                b
                    .setSubquery(
                        plan.subquery.toBuilder().setSubquery(
                            rewrite(plan.subquery.subquery, defaultSchema, mismatches),
                        ),
                    ).build()
            // Store (write-plan root): concretize the write `target` and rewrite the
            // `input` read subtree so both target the worker's advertised schema.
            PlanNode.NodeCase.STORE -> {
                val storeBuilder =
                    plan.store
                        .toBuilder()
                        .setInput(rewrite(plan.store.input, defaultSchema, mismatches))
                concretize(plan.store.target, defaultSchema, mismatches)?.let { storeBuilder.setTarget(it) }
                b.setStore(storeBuilder).build()
            }
            PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.VALUES,
            PlanNode.NodeCase.NODE_NOT_SET,
            PlanNode.NodeCase.ROW_WRITES,
            -> plan
        }
    }

    /**
     * Returns the rewritten [QualifiedName] when the namespace was filled in, null when the
     * qname is left alone (either it already matches, the schema_code isn't DB, or it
     * explicitly names a different schema — in which case a [Mismatch] is recorded).
     */
    private fun concretize(
        qname: QualifiedName,
        defaultSchema: String,
        mismatches: MutableList<Mismatch>,
    ): QualifiedName? {
        if (qname.schemaCode != SchemaCode.DB) return null
        val ns = qname.namespace
        return when {
            ns.isEmpty() -> qname.toBuilder().setNamespace(defaultSchema).build()
            ns == defaultSchema -> null
            else -> {
                mismatches.add(Mismatch(qname, ns, defaultSchema))
                null
            }
        }
    }
}

/**
 * Detects whether a PlanNode tree contains any [PlanNode.NodeCase.WORKSPACE_REF] leaf.
 * Used by Phase 2.4 routing to decide whether the plan is workspace-scoped
 * and therefore eligible for workspace-resident connection strategies.
 */
internal object WorkspaceRefDetector {
    fun hasWorkspaceRef(plan: PlanNode): Boolean =
        when (plan.nodeCase) {
            PlanNode.NodeCase.WORKSPACE_REF -> true
            PlanNode.NodeCase.TABLE_SCAN, PlanNode.NodeCase.SCAN -> false
            PlanNode.NodeCase.PROJECT -> hasWorkspaceRef(plan.project.input)
            PlanNode.NodeCase.FILTER -> hasWorkspaceRef(plan.filter.input)
            PlanNode.NodeCase.JOIN -> hasWorkspaceRef(plan.join.left) || hasWorkspaceRef(plan.join.right)
            PlanNode.NodeCase.UNION -> plan.union.inputsList.any { hasWorkspaceRef(it) }
            PlanNode.NodeCase.AGGREGATE -> hasWorkspaceRef(plan.aggregate.input)
            PlanNode.NodeCase.SORT -> hasWorkspaceRef(plan.sort.input)
            PlanNode.NodeCase.LIMIT_OFFSET -> hasWorkspaceRef(plan.limitOffset.input)
            PlanNode.NodeCase.SUBQUERY -> hasWorkspaceRef(plan.subquery.subquery)
            PlanNode.NodeCase.STORE -> hasWorkspaceRef(plan.store.input)
            PlanNode.NodeCase.VALUES, PlanNode.NodeCase.NODE_NOT_SET, PlanNode.NodeCase.ROW_WRITES -> false
        }
}
