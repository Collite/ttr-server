// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.CastExpression
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.OverExpression
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.SubqueryExpression
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.UnionNode
import org.tatrman.validate.client.LocalPolicyClient
import org.tatrman.validate.policy.Policy
import org.tatrman.validate.policy.PolicyEngine
import org.tatrman.validate.policy.PolicyPredicate
import org.tatrman.validate.policy.PolicyRegistry
import org.tatrman.validate.policy.PolicyValue
import org.tatrman.validate.policy.TableMatcher

/**
 * LR C-5·3 — a row policy reaches every place a plan reads its table: a scan inside an expression
 * subquery (`EXISTS` / `IN` / scalar — in a filter, a projection or a join condition, also under a
 * CAST or a window's OVER) and every branch of a UNION.
 *
 * Two failures this pins, and they were different halves of the same path. The engine collected
 * tables from the plan tree only, so a table read only inside a subquery got no predicate at all.
 * The wrap went through the translator's child walker, which has no UNION case, so a UNION branch's
 * table got a predicate the wrap never placed — and the receipt still said the rule was applied.
 *
 * The check is deliberately not the production walker: [planNodes] walks the proto by reflection,
 * every message field, so a node kind or expression kind the production walk forgets is still counted.
 */
class SecurityReachSpec :
    StringSpec({
        val catalogSales = db("catalog_sales")
        val webSales = db("web_sales")
        val item = db("item")
        val order =
            QualifiedName
                .newBuilder()
                .setSchemaCode(
                    SchemaCode.ER,
                ).setNamespace("entity")
                .setName("Order")
                .build()
        val returnEntity =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName("Return")
                .build()

        val policies =
            listOf(
                scope("dc_cs", catalogSales, "cs_warehouse_sk"),
                scope("dc_ws", webSales, "ws_warehouse_sk"),
                scope("dc_order", order, "warehouse"),
                scope("dc_return", returnEntity, "warehouse"),
            )
        val applier = SecurityApplier(LocalPolicyClient(PolicyEngine(PolicyRegistry(policies))))
        val restricted =
            mapOf(
                catalogSales to "cs_warehouse_sk",
                webSales to "ws_warehouse_sk",
                order to "warehouse",
                returnEntity to "warehouse",
            )

        // SELECT * FROM item WHERE EXISTS (SELECT 1 FROM catalog_sales WHERE cs_item_sk = i_item_sk)
        val existsInFilter =
            filter(subquery("exists", filter(eq(col("cs_item_sk"), col("i_item_sk")), scan(catalogSales))), scan(item))

        // SELECT i_item_sk IN (SELECT ws_item_sk FROM web_sales) AS sold_online FROM item
        val inInProjection = project(scan(item), subquery("in", scan(webSales), col("i_item_sk")))

        // item JOIN catalog_sales ON i_item_sk = (SELECT MAX(ws_item_sk) FROM web_sales)
        val scalarInJoinCondition =
            PlanNode
                .newBuilder()
                .setJoin(
                    JoinNode
                        .newBuilder()
                        .setLeft(scan(item))
                        .setRight(scan(catalogSales))
                        .setJoinType(JoinType.INNER)
                        .setCondition(eq(col("i_item_sk"), subquery("scalar", scan(webSales)))),
                ).build()

        // SUM(x) OVER (PARTITION BY CAST((SELECT ... FROM catalog_sales) AS int)) — exotic, still a read.
        val underCastInOver =
            project(
                scan(item),
                Expression
                    .newBuilder()
                    .setOver(
                        OverExpression
                            .newBuilder()
                            .addOperands(col("i_current_price"))
                            .addPartitionKeys(
                                Expression.newBuilder().setCast(
                                    CastExpression.newBuilder().setValue(subquery("scalar", scan(catalogSales))),
                                ),
                            ),
                    ).build(),
            )

        val dbUnion = union(scan(webSales), scan(catalogSales))
        val entityUnion = union(entityScan(order), entityScan(returnEntity))

        // A UNION inside a subquery inside a filter: both halves of the gap at once.
        val unionInsideSubquery = filter(subquery("exists", dbUnion), scan(item))

        "the engine finds a table read only inside an EXISTS subquery" {
            val result = applier.applied(existsInFilter, PipelineContext.getDefaultInstance())
            result.applied.map { it.ruleId } shouldBe listOf("dc_cs")
        }

        "the subquery's scan is the one wrapped" {
            val result = applier.applied(existsInFilter, PipelineContext.getDefaultInstance())
            val inner = result.plan.filter.condition.subquery.subquery
            inner.filter.input.hasFilter() shouldBe true
            inner.filter.input.filter.input.tableScan.table shouldBe catalogSales
        }

        "both branches of a UNION of DB scans are wrapped" {
            val result = applier.applied(dbUnion, PipelineContext.getDefaultInstance())
            result.plan.union.inputsList
                .map { it.hasFilter() } shouldBe listOf(true, true)
            result.applied.map { it.ruleId } shouldContainExactlyInAnyOrder listOf("dc_cs", "dc_ws")
        }

        "both branches of a UNION of entity scans are wrapped" {
            val result = applier.applied(entityUnion, PipelineContext.getDefaultInstance())
            result.plan.union.inputsList
                .map {
                    it.filter.input.scan
                        .getObject()
                } shouldBe listOf(order, returnEntity)
        }

        listOf(
            "EXISTS in a filter" to existsInFilter,
            "IN in a projection" to inInProjection,
            "a scalar subquery in a join condition" to scalarInJoinCondition,
            "a subquery under CAST in an OVER partition key" to underCastInOver,
            "a UNION of DB scans" to dbUnion,
            "a UNION of entity scans" to entityUnion,
            "a UNION inside an EXISTS subquery" to unionInsideSubquery,
        ).forEach { (shape, plan) ->
            "every scan of a restricted table ends up directly under its policy filter — $shape" {
                val result = applier.applied(plan, PipelineContext.getDefaultInstance())
                val nodes = planNodes(result.plan)
                val readTables = nodes.mapNotNull { scanned(it) }.filter { it in restricted }
                readTables.isNotEmpty() shouldBe true

                val covered =
                    nodes.filter { it.hasFilter() }.mapNotNull { f ->
                        val table = scanned(f.filter.input) ?: return@mapNotNull null
                        table.takeIf { it in restricted && mentions(f.filter.condition, restricted.getValue(it)) }
                    }
                covered shouldContainExactlyInAnyOrder readTables
                // The receipt names exactly the tables that were wrapped — never one that was not.
                result.applied.map { it.ruleId }.toSet() shouldBe
                    readTables
                        .map { t ->
                            policies.first { (it.tableMatch as TableMatcher.Exact).qname == t }.id
                        }.toSet()
                // …and, per entry, the table it restricted (C-5·5).
                result.applied.map { it.table }.toSet() shouldBe readTables.map { dotted(it) }.toSet()
            }
        }
    })

private fun dotted(qn: QualifiedName): String = "${qn.schemaCode.name.lowercase()}.${qn.namespace}.${qn.name}"

private fun db(name: String): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(SchemaCode.DB)
        .setNamespace("dbo")
        .setName(name)
        .build()

private fun scope(
    id: String,
    table: QualifiedName,
    column: String,
): Policy =
    Policy(
        id = id,
        tableMatch = TableMatcher.Exact(table),
        predicate = PolicyPredicate.Eq(column, PolicyValue.Literal(5, "int")),
    )

private fun scan(table: QualifiedName): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(TableScanNode.newBuilder().setTable(table))
        .build()

private fun entityScan(entity: QualifiedName): PlanNode =
    PlanNode
        .newBuilder()
        .setScan(ScanNode.newBuilder().setObject(entity))
        .build()

private fun filter(
    cond: Expression,
    input: PlanNode,
): PlanNode =
    PlanNode
        .newBuilder()
        .setFilter(FilterNode.newBuilder().setCondition(cond).setInput(input))
        .build()

private fun project(
    input: PlanNode,
    expression: Expression,
): PlanNode =
    PlanNode
        .newBuilder()
        .setProject(
            ProjectNode
                .newBuilder()
                .setInput(input)
                .addExpressions(NamedExpression.newBuilder().setExpression(expression).setAlias("x")),
        ).build()

private fun union(vararg inputs: PlanNode): PlanNode =
    PlanNode
        .newBuilder()
        .setUnion(UnionNode.newBuilder().addAllInputs(inputs.toList()))
        .build()

private fun subquery(
    kind: String,
    plan: PlanNode,
    vararg operands: Expression,
): Expression =
    Expression
        .newBuilder()
        .setSubquery(
            SubqueryExpression
                .newBuilder()
                .setKind(kind)
                .setSubquery(plan)
                .addAllOperands(operands.toList()),
        ).setResultType("bool")
        .build()

private fun col(name: String): Expression =
    Expression
        .newBuilder()
        .setColumnRef(ColumnRef.newBuilder().setName(name))
        .build()

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

/** The table or entity a scan node reads; null for every other node. */
private fun scanned(node: PlanNode): QualifiedName? =
    when (node.nodeCase) {
        PlanNode.NodeCase.TABLE_SCAN -> node.tableScan.table
        PlanNode.NodeCase.SCAN -> node.scan.getObject()
        else -> null
    }

/** Every PlanNode anywhere in [root] — found by reflection over every message field, not by a plan walker. */
private fun planNodes(root: Message): List<PlanNode> =
    buildList {
        fun visit(m: Message) {
            if (m is PlanNode) add(m)
            for ((field, value) in m.allFields) {
                if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
                if (field.isRepeated) (value as List<*>).forEach { visit(it as Message) } else visit(value as Message)
            }
        }
        visit(root)
    }

/** Whether [condition] references [column] anywhere. */
private fun mentions(
    condition: Expression,
    column: String,
): Boolean =
    when (condition.exprCase) {
        Expression.ExprCase.COLUMN_REF -> condition.columnRef.name == column
        Expression.ExprCase.FUNCTION -> condition.function.operandsList.any { mentions(it, column) }
        else -> false
    }
