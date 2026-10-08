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
import org.tatrman.plan.v1.schemaCodeToToken

/**
 * PlanNode tree utilities used by the validator stages.
 *
 * Two kinds of operations live here:
 *   - structural inspection (the tables a plan reads or writes, the columns it touches)
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
     * It reads exactly the ground [wrapScans] rewrites — both go through [walk] — so the policy
     * engine can never find a table the wrap does not reach. That disagreement is what this pair
     * replaced: the engine read the tree only (a table in a subquery got no predicate) while the wrap
     * went through the translator's child walker, which has no UNION case (a UNION branch got a
     * predicate nobody placed, and the receipt still said the rule was applied).
     */
    fun scannedTables(plan: PlanNode): Set<QualifiedName> = nodes(plan).mapNotNullTo(LinkedHashSet(), ::scannedTable)

    /**
     * The tables a write plan writes: each `StoreNode`'s `target`. A target is not a scan — no filter
     * can be placed on it — so a row policy that covers one cannot narrow the write (LR C-5·2).
     */
    fun writeTargets(plan: PlanNode): Set<QualifiedName> =
        nodes(plan).filter { it.nodeCase == PlanNode.NodeCase.STORE }.mapTo(LinkedHashSet()) { it.store.target }

    /**
     * Every node of [plan], pre-order — the nodes of expression subqueries included. What the
     * detectors and [scannedTables] read: the same ground [wrapScans] rewrites.
     */
    fun nodes(plan: PlanNode): List<PlanNode> =
        buildList {
            walk(plan, onNode = { add(it) }) { it }
        }

    /** The table or entity [node] scans, or null when it is not a scan. */
    fun scannedTable(node: PlanNode): QualifiedName? =
        when (node.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> node.tableScan.table
            PlanNode.NodeCase.SCAN -> node.scan.getObject()
            else -> null
        }

    /**
     * Rewrites every scan (DB `TableScanNode` or ER `ScanNode`) for which [predicateFor] returns a
     * predicate into `Filter(condition = predicate, input = Scan(...))`, wherever [scannedTables]
     * would find it — one pass for every restricted table.
     *
     * The FilterNode goes directly above the scan, so the security predicate runs on raw rows and
     * every operator above the scan is preserved. The predicate is written in the leaf's own name
     * space (attribute names for ER scans, column names for DB tables).
     */
    fun wrapScans(
        plan: PlanNode,
        predicateFor: (QualifiedName) -> Expression?,
    ): PlanNode = walk(plan) { leaf -> scannedTable(leaf)?.let(predicateFor)?.let { wrapInFilter(leaf, it) } ?: leaf }

    /** [wrapScans] with one [predicate] for every scan whose qname satisfies [isMatch]. */
    fun wrapScans(
        plan: PlanNode,
        predicate: Expression,
        isMatch: (QualifiedName) -> Boolean,
    ): PlanNode = wrapScans(plan) { table -> predicate.takeIf { isMatch(table) } }

    /**
     * The one walk. [onNode] sees every node, pre-order; [onLeaf] sees every leaf — a scan, a
     * workspace ref, VALUES — and its result replaces the leaf. A node none of whose parts changed is
     * returned as it is, so a read-only walk builds nothing.
     */
    private fun walk(
        plan: PlanNode,
        onNode: (PlanNode) -> Unit = {},
        onLeaf: (PlanNode) -> PlanNode,
    ): PlanNode {
        onNode(plan)

        fun node(n: PlanNode): PlanNode = walk(n, onNode, onLeaf)

        fun expr(e: Expression): Expression = walkExpr(e, onNode, onLeaf)
        return when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN,
            PlanNode.NodeCase.SCAN,
            PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.VALUES,
            -> onLeaf(plan)
            PlanNode.NodeCase.NODE_NOT_SET -> plan
            PlanNode.NodeCase.PROJECT -> {
                val p = plan.project
                val input = if (p.hasInput()) node(p.input) else p.input
                val exprs = p.expressionsList.map { ne -> ne.mapExpression(::expr) }
                if (input === p.input && exprs.sameAs(p.expressionsList)) {
                    plan
                } else {
                    val b = p.toBuilder().clearExpressions().addAllExpressions(exprs)
                    if (p.hasInput()) b.input = input
                    plan.toBuilder().setProject(b).build()
                }
            }
            PlanNode.NodeCase.FILTER -> {
                val f = plan.filter
                val input = if (f.hasInput()) node(f.input) else f.input
                val condition = if (f.hasCondition()) expr(f.condition) else f.condition
                if (input === f.input && condition === f.condition) {
                    plan
                } else {
                    val b = f.toBuilder()
                    if (f.hasInput()) b.input = input
                    if (f.hasCondition()) b.condition = condition
                    plan.toBuilder().setFilter(b).build()
                }
            }
            PlanNode.NodeCase.JOIN -> {
                val j = plan.join
                val left = if (j.hasLeft()) node(j.left) else j.left
                val right = if (j.hasRight()) node(j.right) else j.right
                val condition = if (j.hasCondition()) expr(j.condition) else j.condition
                if (left === j.left && right === j.right && condition === j.condition) {
                    plan
                } else {
                    val b = j.toBuilder()
                    if (j.hasLeft()) b.left = left
                    if (j.hasRight()) b.right = right
                    if (j.hasCondition()) b.condition = condition
                    plan.toBuilder().setJoin(b).build()
                }
            }
            PlanNode.NodeCase.UNION -> {
                val inputs = plan.union.inputsList.map(::node)
                if (inputs.sameAs(plan.union.inputsList)) {
                    plan
                } else {
                    plan
                        .toBuilder()
                        .setUnion(
                            plan.union
                                .toBuilder()
                                .clearInputs()
                                .addAllInputs(inputs),
                        ).build()
                }
            }
            PlanNode.NodeCase.AGGREGATE ->
                withInput(plan, plan.aggregate.hasInput(), plan.aggregate.input, ::node) {
                    plan.toBuilder().setAggregate(plan.aggregate.toBuilder().setInput(it)).build()
                }
            PlanNode.NodeCase.SORT ->
                withInput(plan, plan.sort.hasInput(), plan.sort.input, ::node) {
                    plan.toBuilder().setSort(plan.sort.toBuilder().setInput(it)).build()
                }
            PlanNode.NodeCase.LIMIT_OFFSET ->
                withInput(plan, plan.limitOffset.hasInput(), plan.limitOffset.input, ::node) {
                    plan.toBuilder().setLimitOffset(plan.limitOffset.toBuilder().setInput(it)).build()
                }
            PlanNode.NodeCase.SUBQUERY ->
                withInput(plan, plan.subquery.hasSubquery(), plan.subquery.subquery, ::node) {
                    plan.toBuilder().setSubquery(plan.subquery.toBuilder().setSubquery(it)).build()
                }
            // Store (write-plan root): the rows written are read by `input`, which is filtered like any
            // read plan. The write `target` is a qname field, not a scan — never wrapped; see
            // [writeTargets].
            PlanNode.NodeCase.STORE ->
                withInput(plan, plan.store.hasInput(), plan.store.input, ::node) {
                    plan.toBuilder().setStore(plan.store.toBuilder().setInput(it)).build()
                }
        }
    }

    /** A single-input node: [plan] itself when its input did not change, else [rebuild] with the new one. */
    private inline fun withInput(
        plan: PlanNode,
        hasInput: Boolean,
        input: PlanNode,
        node: (PlanNode) -> PlanNode,
        rebuild: (PlanNode) -> PlanNode,
    ): PlanNode {
        if (!hasInput) return plan
        val walked = node(input)
        return if (walked === input) plan else rebuild(walked)
    }

    /** Every expression kind that can hold a nested expression — a subquery can sit under any of them. */
    private fun walkExpr(
        e: Expression,
        onNode: (PlanNode) -> Unit,
        onLeaf: (PlanNode) -> PlanNode,
    ): Expression {
        fun expr(x: Expression): Expression = walkExpr(x, onNode, onLeaf)
        return when (e.exprCase) {
            Expression.ExprCase.SUBQUERY -> {
                val sq = e.subquery
                val inner = if (sq.hasSubquery()) walk(sq.subquery, onNode, onLeaf) else sq.subquery
                val operands = sq.operandsList.map(::expr)
                if (inner === sq.subquery && operands.sameAs(sq.operandsList)) {
                    e
                } else {
                    val b = sq.toBuilder().clearOperands().addAllOperands(operands)
                    if (sq.hasSubquery()) b.subquery = inner
                    e.toBuilder().setSubquery(b).build()
                }
            }
            Expression.ExprCase.FUNCTION -> {
                val operands = e.function.operandsList.map(::expr)
                if (operands.sameAs(e.function.operandsList)) {
                    e
                } else {
                    e
                        .toBuilder()
                        .setFunction(
                            e.function
                                .toBuilder()
                                .clearOperands()
                                .addAllOperands(operands),
                        ).build()
                }
            }
            Expression.ExprCase.CAST ->
                if (!e.cast.hasValue()) {
                    e
                } else {
                    val value = expr(e.cast.value)
                    if (value === e.cast.value) e else e.toBuilder().setCast(e.cast.toBuilder().setValue(value)).build()
                }
            Expression.ExprCase.OVER -> {
                val o = e.over
                val operands = o.operandsList.map(::expr)
                val partitions = o.partitionKeysList.map(::expr)
                val orders =
                    o.orderKeysList.map { k ->
                        if (!k.hasExpr()) {
                            k
                        } else {
                            val walked = expr(k.expr)
                            if (walked === k.expr) k else k.toBuilder().setExpr(walked).build()
                        }
                    }
                if (operands.sameAs(o.operandsList) &&
                    partitions.sameAs(o.partitionKeysList) &&
                    orders.sameAs(o.orderKeysList)
                ) {
                    e
                } else {
                    e
                        .toBuilder()
                        .setOver(
                            o
                                .toBuilder()
                                .clearOperands()
                                .addAllOperands(operands)
                                .clearPartitionKeys()
                                .addAllPartitionKeys(partitions)
                                .clearOrderKeys()
                                .addAllOrderKeys(orders),
                        ).build()
                }
            }
            Expression.ExprCase.COLUMN_REF,
            Expression.ExprCase.LITERAL,
            Expression.ExprCase.PARAMETER,
            Expression.ExprCase.EXPR_NOT_SET,
            -> e
        }
    }

    private fun NamedExpression.mapExpression(f: (Expression) -> Expression): NamedExpression {
        if (!hasExpression()) return this
        val walked = f(expression)
        return if (walked === expression) this else toBuilder().setExpression(walked).build()
    }

    /** Element-wise identity: the walk changed nothing in this list. */
    private fun <T> List<T>.sameAs(other: List<T>): Boolean =
        size == other.size && indices.all { this[it] === other[it] }

    private fun wrapInFilter(
        plan: PlanNode,
        predicate: Expression,
    ): PlanNode =
        PlanNode
            .newBuilder()
            .setFilter(FilterNode.newBuilder().setInput(plan).setCondition(predicate))
            .build()
}

/** `schema.namespace.name` ("db.dbo.inventory", "er.entity.Order") — how validate names a table in every message. */
internal fun QualifiedName.dotted(): String = "${schemaCodeToToken(schemaCode)}.$namespace.$name"

/**
 * Phase 2.4 — whether a plan reads a session-scoped workspace anywhere (subqueries included). A
 * workspace was filtered when it was produced, so the SecurityApplier never wraps one; the tables
 * the plan reads beside it are filtered as usual. The Validator says so with a
 * `security_skipped_for_workspace` warning.
 */
internal object WorkspaceRefDetector {
    fun hasWorkspaceRef(plan: PlanNode): Boolean =
        PlanWalker.nodes(plan).any { it.nodeCase == PlanNode.NodeCase.WORKSPACE_REF }
}

/**
 * §60 — detects mixed-layer trees (both `ScanNode` with ER schemaCode and `TableScanNode`
 * with DB schemaCode in the same plan — UNION branches, a write's input and expression
 * subqueries included). The Validator rejects such trees with `validator_mixed_layer_tree`
 * before any other processing.
 */
internal object MixedLayerDetector {
    fun hasMixedLayers(plan: PlanNode): Boolean {
        val nodes = PlanWalker.nodes(plan)
        return nodes.any { it.nodeCase == PlanNode.NodeCase.SCAN } &&
            nodes.any { it.nodeCase == PlanNode.NodeCase.TABLE_SCAN }
    }

    /**
     * §62 — collects all model object qnames (ER entities, DB tables) referenced by leaf scans
     * in the plan. Used to populate `PipelineContext.used_objects` for audit and lineage tracking.
     */
    fun collectUsedObjects(plan: PlanNode): List<org.tatrman.plan.v1.ObjectRef> =
        PlanWalker.nodes(plan).mapNotNull { node ->
            val table = PlanWalker.scannedTable(node) ?: return@mapNotNull null
            org.tatrman.plan.v1.ObjectRef
                .newBuilder()
                .setSchemaCode(table.schemaCode)
                .setKind(if (node.nodeCase == PlanNode.NodeCase.TABLE_SCAN) "table" else "entity")
                .setQualifiedName(table.dotted())
                .build()
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
    /**
     * The tables and entities a plan reads ([PlanWalker.scannedTables]: DB and ER scans, UNION branches,
     * subqueries) and the tables it writes. A DENY rule fires on either surface: conservative
     * (deny-leaning) posture.
     */
    fun tableQnames(plan: PlanNode): Set<QualifiedName> = PlanWalker.scannedTables(plan) + PlanWalker.writeTargets(plan)

    /** Every column name the plan's nodes touch, on the same ground as [tableQnames]. */
    fun columnNames(plan: PlanNode): Set<String> =
        buildSet {
            PlanWalker.nodes(plan).forEach { node -> collectColumns(node, this) }
        }

    /** The columns ONE node names; its inputs and its subqueries' plans are nodes of their own. */
    private fun collectColumns(
        plan: PlanNode,
        acc: MutableSet<String>,
    ) {
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> plan.tableScan.outputColumnsList.forEach { acc.add(it.name) }
            PlanNode.NodeCase.SCAN -> plan.scan.outputColumnsList.forEach { acc.add(it.name) }
            PlanNode.NodeCase.PROJECT ->
                plan.project.expressionsList.forEach {
                    collectColumnsInExpr(
                        it.expression,
                        acc,
                    )
                }
            PlanNode.NodeCase.FILTER -> collectColumnsInExpr(plan.filter.condition, acc)
            PlanNode.NodeCase.JOIN -> collectColumnsInExpr(plan.join.condition, acc)
            PlanNode.NodeCase.AGGREGATE -> {
                plan.aggregate.groupKeysList.forEach { acc.add(it.name) }
                plan.aggregate.aggregatesList.forEach { call ->
                    call.argsList.forEach { acc.add(it.name) }
                    call.withinGroupList.forEach { acc.add(it.column.name) }
                }
            }
            PlanNode.NodeCase.SORT -> plan.sort.sortKeysList.forEach { acc.add(it.column.name) }
            // Store (write-plan root): its `input` read subtree already carries the grain-key + measure
            // columns as a projection, and that subtree is walked as nodes of its own.
            PlanNode.NodeCase.STORE,
            PlanNode.NodeCase.UNION,
            PlanNode.NodeCase.LIMIT_OFFSET,
            PlanNode.NodeCase.SUBQUERY,
            PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.VALUES,
            PlanNode.NodeCase.NODE_NOT_SET,
            -> Unit
        }
    }

    /** Column refs in [e]; a subquery's own plan is walked as nodes, its LHS operands here. */
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
            Expression.ExprCase.SUBQUERY -> e.subquery.operandsList.forEach { collectColumnsInExpr(it, acc) }
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
