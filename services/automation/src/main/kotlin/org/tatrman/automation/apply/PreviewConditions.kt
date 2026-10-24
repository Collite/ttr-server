// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.runner.*
import org.tatrman.plan.v1.*
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.PhysicalType
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.emit.sql.*
import org.tatrman.ttrp.expr.*
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.graph.model.NodeExpressions
import org.tatrman.ttrp.parser.TtrpParser
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/** Compiler-lowered predicates over one verified route snapshot; no door or model connection. */
internal object PreviewConditions {
    fun select(
        expression: String,
        table: Table,
        bindings: Map<String, BoundValue>,
    ): Set<Int> =
        try {
            compileAndSelect(expression, table, bindings)
        } catch (e: AutomationException) {
            throw e
        } catch (_: Exception) {
            // Neither generated SQL nor a JDBC error containing bound data belongs in the public error.
            unsupported()
        }

    private fun compileAndSelect(
        source: String,
        table: Table,
        bindings: Map<String, BoundValue>,
    ): Set<Int> {
        val parsed = TtrpParser.parseExpression(source)
        if (parsed.diagnostics.any { it.severity == Severity.ERROR }) unsupported()
        val columns = table.columns.toMutableList()
        val constants = mutableListOf<Any?>()
        val authoredNames =
            NodeExpressions
                .walk(
                    parsed.expression,
                ).filterIsInstance<ColumnRef>()
                .map { it.column }
                .toSet()

        fun fresh(): String =
            generateSequence(columns.size) { it + 1 }
                .map { "_au_condition_$it" }
                .first { name -> name !in authoredNames && columns.none { it.name == name } }

        fun constant(
            value: BoundValue,
            location: org.tatrman.ttrp.ast.SourceLocation,
        ): ColumnRef {
            val name = fresh()
            columns += ManifestColumn(name, canonical(value.type))
            constants += (value.value as? Instant)?.atOffset(ZoneOffset.UTC) ?: value.value
            return ColumnRef(null, name, location)
        }
        // Keep literal decimals/large integers out of plan.v1's Double literal arm. Parameters likewise
        // retain their JDBC types rather than the translator's default decimal/timestamp parameter type.
        val expression =
            NodeExpressions.rewrite(parsed.expression) {
                when (it) {
                    is ParamRef -> constant(bindings[it.name] ?: unsupported(), it.location)
                    is Literal ->
                        (it.value as? LiteralValue.Num)?.let { number ->
                            val decimal = BigDecimal(number.raw)
                            val integer =
                                if (number.raw.none { c -> c == '.' || c == 'e' || c == 'E' }) {
                                    runCatching { decimal.longValueExact() }.getOrNull()
                                } else {
                                    null
                                }
                            val bound = integer?.let { BoundValue("int", it) } ?: BoundValue("decimal", decimal)
                            constant(bound, it.location)
                        }
                    else -> null
                }
            }
        val schema = mapOf("" to columns.map { Column(it.name, expressionType(it.type)) })
        val checker = ExpressionTypechecker()
        val checked = checker.check(expression, schema, aggregatesAllowed = false, predicateExpected = true)
        if (checked.type != TtrpType.Bool || checked.diagnostics.any { it.severity == Severity.ERROR }) unsupported()

        // The compiler allows cross-kind join comparisons. Actions have no implicit key coercion;
        // preserve the preview contract's explicit-cast requirement for incompatible scalar kinds.
        fun compatible(
            a: org.tatrman.ttrp.expr.Expression,
            b: org.tatrman.ttrp.expr.Expression,
        ) {
            val left = checker.check(a, schema, aggregatesAllowed = false).type
            val right = checker.check(b, schema, aggregatesAllowed = false).type
            if (left != null && right != null && left.kind != right.kind) unsupported()
        }
        NodeExpressions.walk(expression).forEach {
            when (it) {
                is FunctionCall -> {
                    // DuckDB accepts division by zero as infinity. Full validity/reject lowering is
                    // needed before division can safely participate in action selection.
                    if (it.function.name == "div") unsupported()
                    if (it.function.name in setOf("eq", "neq", "lt", "lte", "gt", "gte")) {
                        compatible(it.args[0], it.args[1])
                    }
                }
                is InList -> it.items.forEach { item -> compatible(it.expr, item) }
                else -> Unit
            }
        }
        // Load only referenced columns; unrelated route data never enters this scratch database.
        val referenced =
            NodeExpressions
                .walk(expression)
                .filterIsInstance<ColumnRef>()
                .map { it.column }
                .toSet()
        val ordinals = columns.indices.filter { columns[it].name in referenced }
        val index = fresh()
        val selectedColumns = ordinals.map { columns[it] } + ManifestColumn(index, "int")
        val staged =
            Table(
                selectedColumns,
                table.rows.mapIndexed { i, row ->
                    val values = row + constants
                    ordinals.map { values[it] } + i.toLong()
                },
            )
        val name =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("au_condition")
                .build()
        val scan = PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(name)).build()
        val filtered =
            PlanNode
                .newBuilder()
                .setFilter(
                    FilterNode.newBuilder().setInput(scan).setCondition(PlanNodeBuilder().expr(expression)),
                ).build()
        val projected =
            PlanNode
                .newBuilder()
                .setProject(
                    ProjectNode.newBuilder().setInput(filtered).addExpressions(
                        NamedExpression.newBuilder().setAlias(index).setExpression(
                            org.tatrman.plan.v1.Expression.newBuilder().setColumnRef(
                                org.tatrman.plan.v1.ColumnRef
                                    .newBuilder()
                                    .setName(index),
                            ),
                        ),
                    ),
                ).build()
        val model = ModelTable(name, staged.columns.mapIndexed { i, c -> modelColumn(c, staged.rows.map { it[i] }) })
        val sql =
            SqlTextRender.doubleQuoted(
                SqlTextRender.unqualify(
                    TranslatorFacade(IslandModelHandle(listOf(model)), SqlDialect.DUCKDB).unparse(projected),
                    "dbo",
                ),
            )
        return DuckDbCombiner().use { db ->
            db.load("au_condition", staged)
            db
                .query(sql, emptyMap())
                .rows
                .map { (it.single() as Long).toInt() }
                .toSet()
        }
    }

    private fun expressionType(type: String): TtrpType =
        TtrpType.parse(if (canonical(type) == "timestamptz") "datetime" else canonical(type))

    private fun canonical(type: String): String =
        when (type.substringBefore('(').lowercase()) {
            "integer", "bigint" -> "int"
            "numeric" -> "decimal"
            "double", "real" -> "float"
            "boolean" -> "bool"
            "datetime" -> "timestamptz"
            "string" -> "text"
            else -> type.substringBefore('(').lowercase()
        }

    private fun modelColumn(
        column: ManifestColumn,
        values: List<Any?>,
    ): ModelColumn {
        val type = canonical(column.type)
        val kind =
            when (type) {
                "int" -> PhysicalType.Kind.BIGINT
                "decimal" -> PhysicalType.Kind.DECIMAL
                "float" -> PhysicalType.Kind.DOUBLE
                "bool" -> PhysicalType.Kind.BOOLEAN
                "date" -> PhysicalType.Kind.DATE
                "timestamp", "timestamptz" -> PhysicalType.Kind.TIMESTAMP
                "text" -> PhysicalType.Kind.VARCHAR
                else -> unsupported()
            }
        val physical =
            if (type == "decimal") {
                val scale = values.filterIsInstance<BigDecimal>().maxOfOrNull { it.scale().coerceAtLeast(0) } ?: 0
                if (scale > 38 ||
                    values.filterIsInstance<BigDecimal>().any { it.precision() - it.scale() > 38 - scale }
                ) {
                    unsupported()
                }
                PhysicalType(kind, 38, scale)
            } else {
                PhysicalType(kind)
            }
        return ModelColumn(column.name, TypeMapping.surfaceType(type), physicalType = physical)
    }

    private fun unsupported(): Nothing =
        throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "unsupported action condition")
}
