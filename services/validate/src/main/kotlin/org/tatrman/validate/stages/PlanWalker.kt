// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.plan.v1.CastExpression
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName

/**
 * PlanNode tree utilities used by the validator stages.
 *
 * Two kinds of operations live here:
 *   - structural inspection (the tables a plan reads, the columns it touches)
 *   - structural rewriting (wrap a scan with a security Filter, mask columns).
 *
 * Both are pure: they return a new `PlanNode` and never mutate inputs. Calcite
 * RelNodes are immutable too; this matches that contract.
 *
 * Wrapping policy (used by the SecurityApplier):
 *   - When multiple `TablePredicate`s target the same table the caller AND-s
 *     them into a single Expression first (AndPredicates.merge), then this
 *     walker wraps each TableScan that matches the predicate's table with a
 *     single FilterNode whose condition is the merged expression.
 *   - The FilterNode is inserted directly above the TableScan, before any
 *     Project/Sort/etc., so the security predicate runs on raw rows. Operators
 *     that already wrap the TableScan are preserved above the new Filter.
 */
internal object PlanWalker {
    /**
     * Every table / entity the plan reads (LR C-5·3): the scans in the plan tree — UNION branches
     * included — AND the scans inside expression subqueries (`EXISTS` / `IN` / scalar, in a
     * projection, a filter or a join condition, also under a CAST, a function or a window's OVER).
     *
     * It walks exactly the ground [wrapScans] walks — both go through [rewriteScans] — so the policy
     * engine can never find a table the wrap does not reach. That disagreement is what this pair
     * replaced: the engine read the tree only (a table in a subquery got no predicate) while the wrap
     * went through the translator's child walker, which has no UNION case (a UNION branch got a
     * predicate nobody placed, and the receipt still said the rule was applied).
     */
    fun scannedTables(plan: PlanNode): Set<QualifiedName> =
        buildSet {
            rewriteScans(plan) { scan, table ->
                add(table)
                scan
            }
        }

    /**
     * Rewrites every scan (DB `TableScanNode` or ER `ScanNode`) whose qname satisfies [isMatch] into
     * `Filter(condition = predicate, input = Scan(...))`, wherever [scannedTables] would find it.
     *
     * The FilterNode goes directly above the scan, so the security predicate runs on raw rows and
     * every operator above the scan is preserved. The predicate is written in the leaf's own name
     * space (attribute names for ER scans, column names for DB tables).
     */
    fun wrapScans(
        plan: PlanNode,
        predicate: Expression,
        isMatch: (QualifiedName) -> Boolean,
    ): PlanNode = rewriteScans(plan) { scan, table -> if (isMatch(table)) wrapInFilter(scan, predicate) else scan }

    /** The one walk: [onScan] sees every scan [plan] reads, and its result replaces the scan. */
    private fun rewriteScans(
        plan: PlanNode,
        onScan: (PlanNode, QualifiedName) -> PlanNode,
    ): PlanNode {
        fun node(n: PlanNode): PlanNode = rewriteScans(n, onScan)

        fun expr(e: Expression): Expression = rewriteExprScans(e, onScan)
        return when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> onScan(plan, plan.tableScan.table)
            PlanNode.NodeCase.SCAN -> onScan(plan, plan.scan.getObject())
            PlanNode.NodeCase.PROJECT -> {
                val p = plan.project.toBuilder()
                if (plan.project.hasInput()) p.input = node(plan.project.input)
                p.clearExpressions()
                plan.project.expressionsList.forEach { ne ->
                    p.addExpressions(
                        if (ne.hasExpression()) ne.toBuilder().setExpression(expr(ne.expression)).build() else ne,
                    )
                }
                plan.toBuilder().setProject(p).build()
            }
            PlanNode.NodeCase.FILTER -> {
                val f = plan.filter.toBuilder()
                if (plan.filter.hasInput()) f.input = node(plan.filter.input)
                if (plan.filter.hasCondition()) f.condition = expr(plan.filter.condition)
                plan.toBuilder().setFilter(f).build()
            }
            PlanNode.NodeCase.JOIN -> {
                val j = plan.join.toBuilder()
                if (plan.join.hasLeft()) j.left = node(plan.join.left)
                if (plan.join.hasRight()) j.right = node(plan.join.right)
                if (plan.join.hasCondition()) j.condition = expr(plan.join.condition)
                plan.toBuilder().setJoin(j).build()
            }
            PlanNode.NodeCase.UNION ->
                plan
                    .toBuilder()
                    .setUnion(
                        plan.union
                            .toBuilder()
                            .clearInputs()
                            .addAllInputs(plan.union.inputsList.map(::node)),
                    ).build()
            PlanNode.NodeCase.AGGREGATE ->
                if (plan.aggregate.hasInput()) {
                    plan
                        .toBuilder()
                        .setAggregate(
                            plan.aggregate.toBuilder().setInput(node(plan.aggregate.input)),
                        ).build()
                } else {
                    plan
                }
            PlanNode.NodeCase.SORT ->
                if (plan.sort.hasInput()) {
                    plan
                        .toBuilder()
                        .setSort(
                            plan.sort.toBuilder().setInput(node(plan.sort.input)),
                        ).build()
                } else {
                    plan
                }
            PlanNode.NodeCase.LIMIT_OFFSET ->
                if (plan.limitOffset.hasInput()) {
                    plan
                        .toBuilder()
                        .setLimitOffset(
                            plan.limitOffset.toBuilder().setInput(node(plan.limitOffset.input)),
                        ).build()
                } else {
                    plan
                }
            PlanNode.NodeCase.SUBQUERY ->
                if (plan.subquery.hasSubquery()) {
                    plan
                        .toBuilder()
                        .setSubquery(
                            plan.subquery.toBuilder().setSubquery(node(plan.subquery.subquery)),
                        ).build()
                } else {
                    plan
                }
            // Store (write-plan root): the rows written are read by `input`, which is filtered like any
            // read plan. The write `target` is a qname field, not a scan — never wrapped.
            PlanNode.NodeCase.STORE ->
                if (plan.store.hasInput()) {
                    plan
                        .toBuilder()
                        .setStore(
                            plan.store.toBuilder().setInput(node(plan.store.input)),
                        ).build()
                } else {
                    plan
                }
            // A workspace was filtered when it was produced (the Validator skips security for
            // workspace-rooted plans); VALUES reads no table.
            PlanNode.NodeCase.WORKSPACE_REF, PlanNode.NodeCase.VALUES, PlanNode.NodeCase.NODE_NOT_SET -> plan
        }
    }

    /** Every expression kind that can hold a nested expression — a subquery can sit under any of them. */
    private fun rewriteExprScans(
        e: Expression,
        onScan: (PlanNode, QualifiedName) -> PlanNode,
    ): Expression {
        fun expr(x: Expression): Expression = rewriteExprScans(x, onScan)
        return when (e.exprCase) {
            Expression.ExprCase.SUBQUERY -> {
                val sq = e.subquery.toBuilder()
                if (e.subquery.hasSubquery()) sq.subquery = rewriteScans(e.subquery.subquery, onScan)
                sq.clearOperands().addAllOperands(e.subquery.operandsList.map(::expr))
                e.toBuilder().setSubquery(sq).build()
            }
            Expression.ExprCase.FUNCTION ->
                e
                    .toBuilder()
                    .setFunction(
                        e.function
                            .toBuilder()
                            .clearOperands()
                            .addAllOperands(e.function.operandsList.map(::expr)),
                    ).build()
            Expression.ExprCase.CAST ->
                if (e.cast.hasValue()) {
                    e
                        .toBuilder()
                        .setCast(
                            e.cast.toBuilder().setValue(expr(e.cast.value)),
                        ).build()
                } else {
                    e
                }
            Expression.ExprCase.OVER -> {
                val o = e.over.toBuilder()
                o.clearOperands().addAllOperands(e.over.operandsList.map(::expr))
                o.clearPartitionKeys().addAllPartitionKeys(e.over.partitionKeysList.map(::expr))
                o.clearOrderKeys().addAllOrderKeys(
                    e.over.orderKeysList.map { k ->
                        if (k.hasExpr()) k.toBuilder().setExpr(expr(k.expr)).build() else k
                    },
                )
                e.toBuilder().setOver(o).build()
            }
            Expression.ExprCase.COLUMN_REF,
            Expression.ExprCase.LITERAL,
            Expression.ExprCase.PARAMETER,
            Expression.ExprCase.EXPR_NOT_SET,
            -> e
        }
    }

    private fun wrapInFilter(
        plan: PlanNode,
        predicate: Expression,
    ): PlanNode =
        PlanNode
            .newBuilder()
            .setFilter(FilterNode.newBuilder().setInput(plan).setCondition(predicate))
            .build()
}

/**
 * Phase 2.4 — detects whether a plan contains at least one `WorkspaceRef`
 * leaf. Workspace-rooted plans skip the SecurityApplier (no table to evaluate
 * against; data was already filtered when the workspace was first produced).
 * The Validator orchestrator emits a `security_skipped_for_workspace`
 * informational warning when this returns true.
 */
internal object WorkspaceRefDetector {
    fun hasWorkspaceRef(plan: org.tatrman.plan.v1.PlanNode): Boolean =
        when (plan.nodeCase) {
            org.tatrman.plan.v1.PlanNode.NodeCase.WORKSPACE_REF -> true
            org.tatrman.plan.v1.PlanNode.NodeCase.TABLE_SCAN, org.tatrman.plan.v1.PlanNode.NodeCase.SCAN -> false
            org.tatrman.plan.v1.PlanNode.NodeCase.PROJECT -> hasWorkspaceRef(plan.project.input)
            org.tatrman.plan.v1.PlanNode.NodeCase.FILTER -> hasWorkspaceRef(plan.filter.input)
            org.tatrman.plan.v1.PlanNode.NodeCase.JOIN ->
                hasWorkspaceRef(plan.join.left) || hasWorkspaceRef(plan.join.right)
            org.tatrman.plan.v1.PlanNode.NodeCase.UNION ->
                plan.union.inputsList.any { hasWorkspaceRef(it) }
            org.tatrman.plan.v1.PlanNode.NodeCase.AGGREGATE -> hasWorkspaceRef(plan.aggregate.input)
            org.tatrman.plan.v1.PlanNode.NodeCase.SORT -> hasWorkspaceRef(plan.sort.input)
            org.tatrman.plan.v1.PlanNode.NodeCase.LIMIT_OFFSET -> hasWorkspaceRef(plan.limitOffset.input)
            org.tatrman.plan.v1.PlanNode.NodeCase.SUBQUERY -> hasWorkspaceRef(plan.subquery.subquery)
            org.tatrman.plan.v1.PlanNode.NodeCase.STORE -> hasWorkspaceRef(plan.store.input)
            org.tatrman.plan.v1.PlanNode.NodeCase.VALUES,
            org.tatrman.plan.v1.PlanNode.NodeCase.NODE_NOT_SET,
            -> false
        }
}

/**
 * §60 — detects mixed-layer trees (both `ScanNode` with ER schemaCode and `TableScanNode`
 * with DB schemaCode in the same plan). The Validator rejects such trees with
 * `validator_mixed_layer_tree` before any other processing.
 */
internal object MixedLayerDetector {
    fun hasMixedLayers(plan: org.tatrman.plan.v1.PlanNode): Boolean {
        var hasErScan = false
        var hasTableScan = false
        walk(plan) { node ->
            when (node.nodeCase) {
                org.tatrman.plan.v1.PlanNode.NodeCase.SCAN -> hasErScan = true
                org.tatrman.plan.v1.PlanNode.NodeCase.TABLE_SCAN -> hasTableScan = true
                else -> Unit
            }
        }
        return hasErScan && hasTableScan
    }

    private fun walk(
        plan: org.tatrman.plan.v1.PlanNode,
        visitor: (org.tatrman.plan.v1.PlanNode) -> Unit,
    ) {
        visitor(plan)
        when (plan.nodeCase) {
            org.tatrman.plan.v1.PlanNode.NodeCase.PROJECT -> walk(plan.project.input, visitor)
            org.tatrman.plan.v1.PlanNode.NodeCase.FILTER -> walk(plan.filter.input, visitor)
            org.tatrman.plan.v1.PlanNode.NodeCase.JOIN -> {
                walk(plan.join.left, visitor)
                walk(plan.join.right, visitor)
            }
            org.tatrman.plan.v1.PlanNode.NodeCase.AGGREGATE -> walk(plan.aggregate.input, visitor)
            org.tatrman.plan.v1.PlanNode.NodeCase.SORT -> walk(plan.sort.input, visitor)
            org.tatrman.plan.v1.PlanNode.NodeCase.LIMIT_OFFSET -> walk(plan.limitOffset.input, visitor)
            org.tatrman.plan.v1.PlanNode.NodeCase.SUBQUERY -> walk(plan.subquery.subquery, visitor)
            else -> Unit
        }
    }

    /**
     * §62 — collects all model object qnames (ER entities, DB tables) referenced by leaf scans
     * in the plan. Used to populate `PipelineContext.used_objects` for audit and lineage tracking.
     */
    fun collectUsedObjects(plan: org.tatrman.plan.v1.PlanNode): List<org.tatrman.plan.v1.ObjectRef> {
        val refs = mutableListOf<org.tatrman.plan.v1.ObjectRef>()
        walk(plan) { node ->
            when (node.nodeCase) {
                org.tatrman.plan.v1.PlanNode.NodeCase.TABLE_SCAN -> {
                    val t = node.tableScan.table
                    refs.add(
                        org.tatrman.plan.v1.ObjectRef
                            .newBuilder()
                            .setSchemaCode(t.schemaCode)
                            .setKind("table")
                            .setQualifiedName(qnameDot(t))
                            .build(),
                    )
                }
                org.tatrman.plan.v1.PlanNode.NodeCase.SCAN -> {
                    val s = node.scan.getObject()
                    refs.add(
                        org.tatrman.plan.v1.ObjectRef
                            .newBuilder()
                            .setSchemaCode(s.schemaCode)
                            .setKind("entity")
                            .setQualifiedName(qnameDot(s))
                            .build(),
                    )
                }
                else -> Unit
            }
        }
        return refs
    }

    private fun qnameDot(qn: org.tatrman.plan.v1.QualifiedName): String =
        buildString {
            append(
                org.tatrman.plan.v1
                    .schemaCodeToToken(qn.schemaCode),
            )
            append('.')
            append(qn.namespace)
            append('.')
            append(qn.name)
        }
}

/**
 * DF-V01 — utilities for column-rule enforcement. Two operations:
 *
 *   - [tableQnames] / [columnNames]: enumerate the (table, column) surface a plan touches so the
 *     enforcer can decide whether a `DENY` rule fires. Conservative: a denied `(T, c)` fires if T
 *     is in `tableQnames(plan)` and `c` is in `columnNames(plan)`. Without RESOLVE-stage column
 *     qualification (Phase 08) we can't always disambiguate which TableScan a bare `ColumnRef`
 *     belongs to; this errs toward denying (safer posture for v1).
 *   - [rewriteColumnRefs]: substitute a `ColumnRef` expression with a `mask_expression` everywhere
 *     it appears wrapped in an [Expression] (project expressions, filter/join conditions, cast
 *     sub-expressions). Bare `ColumnRef` slots (group-by keys, sort keys, aggregate args) can't be
 *     substituted with an arbitrary expression — they're documented as out-of-scope for v1 masking.
 */
internal object ColumnUsage {
    fun tableQnames(plan: PlanNode): Set<QualifiedName> = buildSet { collectTables(plan, this) }

    fun columnNames(plan: PlanNode): Set<String> = buildSet { collectColumns(plan, this) }

    private fun collectTables(
        plan: PlanNode,
        acc: MutableSet<QualifiedName>,
    ) {
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> acc.add(plan.tableScan.table)
            PlanNode.NodeCase.PROJECT -> {
                plan.project.expressionsList.forEach { collectTablesInExpr(it.expression, acc) }
                collectTables(plan.project.input, acc)
            }
            PlanNode.NodeCase.FILTER -> {
                collectTablesInExpr(plan.filter.condition, acc)
                collectTables(plan.filter.input, acc)
            }
            PlanNode.NodeCase.JOIN -> {
                collectTablesInExpr(plan.join.condition, acc)
                collectTables(plan.join.left, acc)
                collectTables(plan.join.right, acc)
            }
            PlanNode.NodeCase.UNION -> plan.union.inputsList.forEach { collectTables(it, acc) }
            PlanNode.NodeCase.AGGREGATE -> collectTables(plan.aggregate.input, acc)
            PlanNode.NodeCase.SORT -> collectTables(plan.sort.input, acc)
            PlanNode.NodeCase.LIMIT_OFFSET -> collectTables(plan.limitOffset.input, acc)
            PlanNode.NodeCase.SUBQUERY -> collectTables(plan.subquery.subquery, acc)
            // Store (write-plan root): the write `target` and the tables read by
            // its `input` are both surfaces a DENY rule may fire on. Conservative
            // (deny-leaning) posture: count both.
            PlanNode.NodeCase.STORE -> {
                acc.add(plan.store.target)
                collectTables(plan.store.input, acc)
            }
            PlanNode.NodeCase.SCAN,
            PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.VALUES,
            PlanNode.NodeCase.NODE_NOT_SET,
            -> Unit
        }
    }

    /**
     * Collects tables referenced *inside an Expression*. The only expression that can introduce a
     * table is a [SubqueryExpression] (scalar / EXISTS / IN): its nested plan can scan completely
     * new tables that never appear in the outer plan tree, so we descend into it via [collectTables].
     * The subquery's left-hand operands (the `x` in `x IN (SELECT ...)`) are walked too — they may
     * themselves wrap further subqueries.
     */
    private fun collectTablesInExpr(
        e: Expression,
        acc: MutableSet<QualifiedName>,
    ) {
        when (e.exprCase) {
            Expression.ExprCase.FUNCTION -> e.function.operandsList.forEach { collectTablesInExpr(it, acc) }
            Expression.ExprCase.OVER -> {
                e.over.operandsList.forEach { collectTablesInExpr(it, acc) }
                e.over.partitionKeysList.forEach { collectTablesInExpr(it, acc) }
                e.over.orderKeysList.forEach { collectTablesInExpr(it.expr, acc) }
            }
            Expression.ExprCase.CAST -> collectTablesInExpr(e.cast.value, acc)
            Expression.ExprCase.SUBQUERY -> {
                e.subquery.operandsList.forEach { collectTablesInExpr(it, acc) }
                collectTables(e.subquery.subquery, acc)
            }
            Expression.ExprCase.COLUMN_REF,
            Expression.ExprCase.LITERAL,
            Expression.ExprCase.PARAMETER,
            Expression.ExprCase.EXPR_NOT_SET,
            -> Unit
        }
    }

    private fun collectColumns(
        plan: PlanNode,
        acc: MutableSet<String>,
    ) {
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> plan.tableScan.outputColumnsList.forEach { acc.add(it.name) }
            PlanNode.NodeCase.PROJECT -> {
                plan.project.expressionsList.forEach { collectColumnsInExpr(it.expression, acc) }
                collectColumns(plan.project.input, acc)
            }
            PlanNode.NodeCase.FILTER -> {
                collectColumnsInExpr(plan.filter.condition, acc)
                collectColumns(plan.filter.input, acc)
            }
            PlanNode.NodeCase.JOIN -> {
                collectColumnsInExpr(plan.join.condition, acc)
                collectColumns(plan.join.left, acc)
                collectColumns(plan.join.right, acc)
            }
            PlanNode.NodeCase.UNION -> plan.union.inputsList.forEach { collectColumns(it, acc) }
            PlanNode.NodeCase.AGGREGATE -> {
                plan.aggregate.groupKeysList.forEach { acc.add(it.name) }
                plan.aggregate.aggregatesList.forEach { call -> call.argsList.forEach { acc.add(it.name) } }
                collectColumns(plan.aggregate.input, acc)
            }
            PlanNode.NodeCase.SORT -> {
                plan.sort.sortKeysList.forEach { acc.add(it.column.name) }
                collectColumns(plan.sort.input, acc)
            }
            PlanNode.NodeCase.LIMIT_OFFSET -> collectColumns(plan.limitOffset.input, acc)
            PlanNode.NodeCase.SUBQUERY -> collectColumns(plan.subquery.subquery, acc)
            // Store (write-plan root): its `input` read subtree already carries the
            // grain-key + measure columns as a projection, so recursing covers them.
            PlanNode.NodeCase.STORE -> collectColumns(plan.store.input, acc)
            PlanNode.NodeCase.SCAN,
            PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.VALUES,
            PlanNode.NodeCase.NODE_NOT_SET,
            -> Unit
        }
    }

    private fun collectColumnsInExpr(
        e: Expression,
        acc: MutableSet<String>,
    ) {
        when (e.exprCase) {
            Expression.ExprCase.COLUMN_REF -> acc.add(e.columnRef.name)
            Expression.ExprCase.FUNCTION -> e.function.operandsList.forEach { collectColumnsInExpr(it, acc) }
            Expression.ExprCase.OVER -> {
                e.over.operandsList.forEach { collectColumnsInExpr(it, acc) }
                e.over.partitionKeysList.forEach { collectColumnsInExpr(it, acc) }
                e.over.orderKeysList.forEach { collectColumnsInExpr(it.expr, acc) }
            }
            Expression.ExprCase.CAST -> collectColumnsInExpr(e.cast.value, acc)
            // A subquery expression carries its own relational plan (which may reference new tables
            // and columns) plus the LHS operands for `IN`. Recurse into both: the nested plan via
            // collectColumns, the operands via collectColumnsInExpr.
            Expression.ExprCase.SUBQUERY -> {
                e.subquery.operandsList.forEach { collectColumnsInExpr(it, acc) }
                collectColumns(e.subquery.subquery, acc)
            }
            Expression.ExprCase.LITERAL,
            Expression.ExprCase.PARAMETER,
            Expression.ExprCase.EXPR_NOT_SET,
            -> Unit
        }
    }
}

/**
 * Rewrites `ColumnRef` leaves inside any `Expression` of the plan via [transform] (returns the
 * replacement Expression or `null` to keep the original). Bare `ColumnRef` slots that aren't
 * wrapped in an `Expression` — group-by keys, sort-key columns, aggregate-call args — are not
 * touched. Returns a new PlanNode (proto messages are immutable).
 */
internal object ExpressionRewriter {
    fun rewriteColumnRefs(
        plan: PlanNode,
        transform: (ColumnRef) -> Expression?,
    ): PlanNode =
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN, PlanNode.NodeCase.SCAN, PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.VALUES, PlanNode.NodeCase.NODE_NOT_SET,
            -> plan
            PlanNode.NodeCase.PROJECT ->
                PlanNode
                    .newBuilder()
                    .setProject(
                        plan.project
                            .toBuilder()
                            .clearExpressions()
                            .addAllExpressions(
                                plan.project.expressionsList.map { ne ->
                                    val rewritten = rewriteExpression(ne.expression, transform)
                                    if (rewritten === ne.expression) {
                                        ne
                                    } else {
                                        NamedExpression
                                            .newBuilder()
                                            .setExpression(rewritten)
                                            .setAlias(ne.alias)
                                            .build()
                                    }
                                },
                            ).setInput(rewriteColumnRefs(plan.project.input, transform)),
                    ).build()
            PlanNode.NodeCase.FILTER ->
                PlanNode
                    .newBuilder()
                    .setFilter(
                        plan.filter
                            .toBuilder()
                            .setCondition(rewriteExpression(plan.filter.condition, transform))
                            .setInput(rewriteColumnRefs(plan.filter.input, transform)),
                    ).build()
            PlanNode.NodeCase.JOIN ->
                PlanNode
                    .newBuilder()
                    .setJoin(
                        plan.join
                            .toBuilder()
                            .setCondition(rewriteExpression(plan.join.condition, transform))
                            .setLeft(rewriteColumnRefs(plan.join.left, transform))
                            .setRight(rewriteColumnRefs(plan.join.right, transform)),
                    ).build()
            PlanNode.NodeCase.UNION ->
                PlanNode
                    .newBuilder()
                    .setUnion(
                        plan.union
                            .toBuilder()
                            .clearInputs()
                            .addAllInputs(plan.union.inputsList.map { rewriteColumnRefs(it, transform) }),
                    ).build()
            PlanNode.NodeCase.AGGREGATE ->
                PlanNode
                    .newBuilder()
                    .setAggregate(
                        plan.aggregate
                            .toBuilder()
                            .setInput(rewriteColumnRefs(plan.aggregate.input, transform)),
                    ).build()
            PlanNode.NodeCase.SORT ->
                PlanNode
                    .newBuilder()
                    .setSort(
                        plan.sort
                            .toBuilder()
                            .setInput(rewriteColumnRefs(plan.sort.input, transform)),
                    ).build()
            PlanNode.NodeCase.LIMIT_OFFSET ->
                PlanNode
                    .newBuilder()
                    .setLimitOffset(
                        plan.limitOffset
                            .toBuilder()
                            .setInput(rewriteColumnRefs(plan.limitOffset.input, transform)),
                    ).build()
            PlanNode.NodeCase.SUBQUERY ->
                PlanNode
                    .newBuilder()
                    .setSubquery(
                        plan.subquery
                            .toBuilder()
                            .setSubquery(rewriteColumnRefs(plan.subquery.subquery, transform)),
                    ).build()
            // Store (write-plan root): mask column refs inside the read subtree that
            // computes the rows being written.
            PlanNode.NodeCase.STORE ->
                PlanNode
                    .newBuilder()
                    .setStore(
                        plan.store
                            .toBuilder()
                            .setInput(rewriteColumnRefs(plan.store.input, transform)),
                    ).build()
        }

    private fun rewriteExpression(
        e: Expression,
        transform: (ColumnRef) -> Expression?,
    ): Expression =
        when (e.exprCase) {
            Expression.ExprCase.COLUMN_REF -> transform(e.columnRef) ?: e
            Expression.ExprCase.FUNCTION -> {
                val rewritten = e.function.operandsList.map { rewriteExpression(it, transform) }
                if (rewritten.zip(e.function.operandsList).all { (a, b) -> a === b }) {
                    e
                } else {
                    e
                        .toBuilder()
                        .setFunction(
                            FunctionCall
                                .newBuilder()
                                .setOperation(e.function.operation)
                                .addAllOperands(rewritten),
                        ).build()
                }
            }
            Expression.ExprCase.OVER ->
                e
                    .toBuilder()
                    .setOver(
                        e.over
                            .toBuilder()
                            .clearOperands()
                            .addAllOperands(e.over.operandsList.map { rewriteExpression(it, transform) })
                            .clearPartitionKeys()
                            .addAllPartitionKeys(e.over.partitionKeysList.map { rewriteExpression(it, transform) })
                            .clearOrderKeys()
                            .addAllOrderKeys(
                                e.over.orderKeysList.map {
                                    it.toBuilder().setExpr(rewriteExpression(it.expr, transform)).build()
                                },
                            ),
                    ).build()
            Expression.ExprCase.CAST -> {
                val inner = rewriteExpression(e.cast.value, transform)
                if (inner === e.cast.value) {
                    e
                } else {
                    e
                        .toBuilder()
                        .setCast(
                            CastExpression
                                .newBuilder()
                                .setValue(inner)
                                .setTargetType(e.cast.targetType),
                        ).build()
                }
            }
            // Rewrite column refs inside the subquery too: its nested plan and its LHS operands can
            // reference columns subject to masking. Delegates the plan to rewriteColumnRefs (which
            // already handles plan-level subqueries) and the operands back through rewriteExpression.
            Expression.ExprCase.SUBQUERY -> {
                val rewrittenPlan = rewriteColumnRefs(e.subquery.subquery, transform)
                val rewrittenOperands = e.subquery.operandsList.map { rewriteExpression(it, transform) }
                val planUnchanged = rewrittenPlan === e.subquery.subquery
                val operandsUnchanged = rewrittenOperands.zip(e.subquery.operandsList).all { (a, b) -> a === b }
                if (planUnchanged && operandsUnchanged) {
                    e
                } else {
                    e
                        .toBuilder()
                        .setSubquery(
                            e.subquery
                                .toBuilder()
                                .setSubquery(rewrittenPlan)
                                .clearOperands()
                                .addAllOperands(rewrittenOperands),
                        ).build()
                }
            }
            Expression.ExprCase.LITERAL,
            Expression.ExprCase.PARAMETER,
            Expression.ExprCase.EXPR_NOT_SET,
            -> e
        }
}

/**
 * AND-merges a list of structured predicates into a single Expression. The
 * SecurityApplier calls this when multiple policies target the same physical
 * table; the resulting expression becomes the FilterNode condition.
 */
internal object AndPredicates {
    fun merge(predicates: List<Expression>): Expression =
        when (predicates.size) {
            0 -> error("merge() requires at least one predicate")
            1 -> predicates.single()
            else ->
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("and")
                            .also { fc -> predicates.forEach(fc::addOperands) },
                    ).setResultType("bool")
                    .build()
        }
}
