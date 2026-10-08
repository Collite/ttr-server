// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SubqueryExpression
import org.tatrman.plan.v1.TableScanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PlanWalkerSpec :
    StringSpec({
        val customers = qname("db", "dbo", "customers")
        val orders = qname("db", "dbo", "orders")
        val tenantPredicate = eq(columnRef("tenant_id"), intLiteral(42))

        "wrap a bare TableScan with a FilterNode" {
            val plan = scan(customers)
            val out = PlanWalker.wrapScans(plan, tenantPredicate) { it == customers }
            out.hasFilter() shouldBe true
            out.filter.input.hasTableScan() shouldBe true
            out.filter.condition shouldBe tenantPredicate
        }

        "preserve operators above the wrapped TableScan" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan(customers))
                            .addExpressions(
                                NamedExpression
                                    .newBuilder()
                                    .setExpression(columnRef("id")),
                            ),
                    ).build()
            val out = PlanWalker.wrapScans(plan, tenantPredicate) { it == customers }
            out.hasProject() shouldBe true
            out.project.input.hasFilter() shouldBe true
            out.project.input.filter.input
                .hasTableScan() shouldBe true
        }

        "leave non-matching TableScans untouched" {
            val plan = scan(orders)
            val out = PlanWalker.wrapScans(plan, tenantPredicate) { it == customers }
            out shouldBe plan
        }

        // review-159 ⑫ — a read-only walk builds nothing; a wrap rebuilds only the path it changed.
        "a walk that changes nothing returns the very same plan" {
            val regions = qname("db", "dbo", "regions")
            val plan = filter(inSubquery(columnRef("country"), scan(regions)), scan(customers))
            (PlanWalker.wrapScans(plan) { null } === plan) shouldBe true
            PlanWalker.scannedTables(plan) shouldBe setOf(customers, regions)
        }

        "one wrap pass places each table's own predicate" {
            val orderPredicate = eq(columnRef("region"), intLiteral(7))
            val plan = filter(inSubquery(columnRef("id"), scan(orders)), scan(customers))
            val out = PlanWalker.wrapScans(plan) { mapOf(customers to tenantPredicate, orders to orderPredicate)[it] }
            out.filter.input.filter.condition shouldBe tenantPredicate
            out.filter.condition.subquery.subquery.filter.condition shouldBe orderPredicate
        }

        "ColumnUsage collects tables and columns from an IN subquery referencing a new table" {
            val regions = qname("db", "dbo", "regions")
            // WHERE country IN (SELECT region_code FROM regions WHERE active = 1)
            val sub = filter(eq(columnRef("active"), intLiteral(1)), scan(regions))
            val plan = filter(inSubquery(columnRef("country"), sub), scan(customers))

            ColumnUsage.tableQnames(plan) shouldBe setOf(customers, regions)
            ColumnUsage.columnNames(plan) shouldBe setOf("country", "active")
        }

        "ExpressionRewriter masks column refs inside a subquery" {
            val regions = qname("db", "dbo", "regions")
            val sub = filter(eq(columnRef("active"), intLiteral(1)), scan(regions))
            val plan = filter(inSubquery(columnRef("country"), sub), scan(customers))

            val masked =
                ExpressionRewriter.rewriteColumnRefs(plan) { ref ->
                    if (ref.name == "active") stringLiteral("***") else null
                }

            val rewrittenSubCondition =
                masked.filter.condition.subquery.subquery.filter.condition
            rewrittenSubCondition.function
                .operandsList[0]
                .hasLiteral() shouldBe true
        }

        "AndPredicates.merge collapses one predicate to itself" {
            AndPredicates.merge(listOf(tenantPredicate)) shouldBe tenantPredicate
        }

        "AndPredicates.merge AND-merges multiple predicates" {
            val second = eq(columnRef("region"), stringLiteral("EU"))
            val merged = AndPredicates.merge(listOf(tenantPredicate, second))
            merged.hasFunction() shouldBe true
            merged.function.operation shouldBe "and"
            merged.function.operandsList.size shouldBe 2
        }
    })

private fun qname(
    schema: String,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(
            if (schema ==
                "obj"
            ) {
                org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
            } else {
                org.tatrman.plan.v1.SchemaCode
                    .valueOf(schema.uppercase())
            },
        ).setNamespace(ns)
        .setName(name)
        .build()

private fun scan(table: QualifiedName): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(TableScanNode.newBuilder().setTable(table))
        .build()

private fun filter(
    cond: Expression,
    input: PlanNode,
): PlanNode =
    PlanNode
        .newBuilder()
        .setFilter(FilterNode.newBuilder().setCondition(cond).setInput(input))
        .build()

private fun inSubquery(
    lhs: Expression,
    subPlan: PlanNode,
): Expression =
    Expression
        .newBuilder()
        .setSubquery(
            SubqueryExpression
                .newBuilder()
                .setKind("in")
                .addOperands(lhs)
                .setSubquery(subPlan),
        ).setResultType("bool")
        .build()

private fun columnRef(name: String): Expression =
    Expression
        .newBuilder()
        .setColumnRef(ColumnRef.newBuilder().setName(name))
        .build()

private fun intLiteral(v: Long): Expression =
    Expression
        .newBuilder()
        .setLiteral(Literal.newBuilder().setIntValue(v).setType("int"))
        .build()

private fun stringLiteral(v: String): Expression =
    Expression
        .newBuilder()
        .setLiteral(Literal.newBuilder().setStringValue(v).setType("text"))
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
