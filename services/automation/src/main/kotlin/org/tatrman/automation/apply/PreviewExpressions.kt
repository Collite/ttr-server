// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

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

/** Compiler-lowered scalar expressions over verified route rows; no door or model connection. */
internal object PreviewExpressions {
    /** Check context constants through the compiler without round-tripping their precision through JDBC. */
    fun constants(
        values: Map<String, BoundValue>,
        expectedTypes: Map<String, String>,
        site: PreviewSite? = null,
    ): Map<String, Any?> {
        if (values.isEmpty()) return emptyMap()
        project(
            values.keys.associateWith { ":$it" },
            Table(emptyList(), emptyList()),
            values,
            expectedTypes,
            site = site,
        )
        val assigned =
            PreviewAssignments.assign(
                Table(
                    values.map { (name, value) ->
                        ManifestColumn(name, value.type)
                    },
                    listOf(values.values.map { it.value }),
                ),
                expectedTypes,
            )
        return values.keys.zip(assigned.rows.single()).toMap()
    }

    /**
     * S2.8: [site] names the wiring of a refusal; [declared] maps an expression's key to its declared argument name
     * where the key is a generated alias (a nested wiring value).
     */
    fun project(
        expressions: Map<String, String>,
        table: Table,
        bindings: Map<String, BoundValue>,
        expectedTypes: Map<String, String> = emptyMap(),
        calendar: CalendarSnapshot? = null,
        site: PreviewSite? = null,
        declared: Map<String, String> = emptyMap(),
    ): Table =
        guarded(
            "unsupported action expression",
            site,
            expression = {
                // One query evaluates every expression: on a refusal, find the first that also fails alone.
                val failing =
                    expressions.keys.singleOrNull() ?: expressions.keys.firstOrNull { key ->
                        runCatching {
                            projectOnce(
                                mapOf(key to expressions.getValue(key)),
                                table,
                                bindings,
                                expectedTypes,
                                calendar,
                            )
                        }.isFailure
                    }
                failing?.let { declared[it] ?: it }
            },
            data = { values(table, bindings) },
        ) {
            projectOnce(expressions, table, bindings, expectedTypes, calendar)
        }

    private fun projectOnce(
        expressions: Map<String, String>,
        table: Table,
        bindings: Map<String, BoundValue>,
        expectedTypes: Map<String, String>,
        calendar: CalendarSnapshot?,
    ): Table {
        val result =
            execute(
                expressions.values.toList(),
                table,
                bindings,
                predicate = false,
                expectedTypes = expressions.keys.map { expectedTypes[it] },
                calendar = calendar,
            )
        return PreviewAssignments.assign(
            Table(
                result.columns.drop(1).mapIndexed { i, c -> c.copy(name = expressions.keys.elementAt(i)) },
                result.rows.sortedBy { (it.first() as Long) }.map { it.drop(1) },
            ),
            expectedTypes,
        )
    }

    fun select(
        expression: String,
        table: Table,
        bindings: Map<String, BoundValue>,
        calendar: CalendarSnapshot? = null,
        site: PreviewSite? = null,
    ): Set<Int> =
        guarded(
            "unsupported action condition",
            site?.copy(kind = "condition"),
            expression = { "when" },
            data = { values(table, bindings) },
        ) {
            execute(listOf(expression), table, bindings, predicate = true, calendar = calendar)
                .rows
                .map { (it.single() as Long).toInt() }
                .toSet()
        }

    private inline fun <T> guarded(
        message: String,
        site: PreviewSite?,
        expression: () -> String?,
        noinline data: () -> PreviewRefusals.Redaction,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Exception) {
            // Neither generated SQL nor a JDBC error containing bound data belongs in the public error (S2.8:
            // the error names the wiring and the cause; the log carries the message with bound values redacted).
            PreviewRefusals.refuse(message, site, runCatching(expression).getOrNull(), e, data)
        }

    /** The values a cause's message could quote: the bindings and the staged route values. */
    internal fun values(
        table: Table,
        bindings: Map<String, BoundValue>,
    ): PreviewRefusals.Redaction =
        PreviewRefusals.Redaction(bindings.values.asSequence().map { it.value }, table.rows.asSequence().flatten())

    private fun execute(
        sources: List<String>,
        table: Table,
        bindings: Map<String, BoundValue>,
        predicate: Boolean,
        expectedTypes: List<String?> = emptyList(),
        calendar: CalendarSnapshot? = null,
    ): Table {
        val parsed = sources.mapIndexed { i, source -> TtrpParser.parseExpression(source, "<action-$i>") }
        parsed
            .firstNotNullOfOrNull { result ->
                result.diagnostics.firstOrNull { it.severity == Severity.ERROR }
            }?.let {
                unsupported(it.id.id, it.message)
            }
        val timed = PreviewTime.lower(parsed.map { it.expression }, bindings)
        val calendarResult = PreviewCalendarGraph.lower(timed.expressions, table, timed.bindings, calendar)
        val columns = calendarResult.table.columns.toMutableList()
        val constants = mutableListOf<Any?>()
        val authoredNames =
            parsed
                .flatMap { NodeExpressions.walk(it.expression).toList() }
                .filterIsInstance<ColumnRef>()
                .map { it.column }
                .toSet()

        fun fresh(): String =
            generateSequence(columns.size) { it + 1 }
                .map { "_au_condition_$it" }
                .first { name ->
                    authoredNames.none { it.equals(name, ignoreCase = true) } &&
                        columns.none { it.name.equals(name, ignoreCase = true) }
                }

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
        val boundExpressions =
            calendarResult.expressions.map { result ->
                NodeExpressions.rewrite(result) {
                    when (it) {
                        is ParamRef ->
                            constant(
                                timed.bindings[it.name] ?: unsupported("unbound-parameter"),
                                it.location,
                            )
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
            }
        val expressions = boundExpressions
        val schema = mapOf("" to columns.map { Column(it.name, expressionType(it.type)) })
        val checker = ExpressionTypechecker()
        expressions.forEachIndexed { i, expression ->
            val checked = checker.check(expression, schema, aggregatesAllowed = false, predicateExpected = predicate)
            checked.diagnostics.firstOrNull { it.severity == Severity.ERROR }?.let { unsupported(it.id.id, it.message) }
            if (predicate && checked.type != TtrpType.Bool) unsupported("condition-not-boolean")
            expectedTypes.getOrNull(i)?.let { type ->
                // Reuse the compiler's result unification, including lossless integer/decimal and
                // date/timestamp widening. A case with a typed NULL is only checked, never executed.
                val target = expressionType(type)
                val assignment =
                    CaseWhen(
                        listOf(Literal(LiteralValue.Bool(true), expression.location) to expression),
                        Cast(Literal(LiteralValue.Null, expression.location), target, expression.location),
                        expression.location,
                    )
                val checkedAssignment = checker.check(assignment, schema, aggregatesAllowed = false)
                // Decimal precision/scale on an authored cast do not change its logical kind;
                // PreviewWriter separately checks bounds preserved by the target metadata.
                checkedAssignment.diagnostics.firstOrNull { it.severity == Severity.ERROR }?.let {
                    unsupported(it.id.id, it.message)
                }
                if (checkedAssignment.type?.canonical != target.canonical) unsupported("assignment-type")
            }
        }

        // The compiler allows cross-kind join comparisons. Actions have no implicit key coercion;
        // preserve the preview contract's explicit-cast requirement for incompatible scalar kinds.
        fun compatible(
            a: org.tatrman.ttrp.expr.Expression,
            b: org.tatrman.ttrp.expr.Expression,
        ) {
            val left = checker.check(a, schema, aggregatesAllowed = false).type
            val right = checker.check(b, schema, aggregatesAllowed = false).type
            if (left != null && right != null && left.kind != right.kind) unsupported("comparison-kinds")
        }
        expressions.flatMap { NodeExpressions.walk(it).toList() }.forEach {
            when (it) {
                is FunctionCall -> {
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
            expressions
                .flatMap { NodeExpressions.walk(it).toList() }
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
                    val values = calendarResult.table.rows[i] + constants
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
        val model = ModelTable(name, staged.columns.mapIndexed { i, c -> modelColumn(c, staged.rows.map { it[i] }) })

        fun sqlFor(
            values: List<org.tatrman.ttrp.expr.Expression>,
            filter: Boolean = false,
        ): String {
            val input =
                if (filter) {
                    PlanNode
                        .newBuilder()
                        .setFilter(
                            FilterNode
                                .newBuilder()
                                .setInput(
                                    scan,
                                ).setCondition(PlanNodeBuilder().expr(values.single())),
                        ).build()
                } else {
                    scan
                }
            val projection =
                ProjectNode.newBuilder().setInput(input).addExpressions(
                    NamedExpression.newBuilder().setAlias(index).setExpression(
                        org.tatrman.plan.v1.Expression.newBuilder().setColumnRef(
                            org.tatrman.plan.v1.ColumnRef
                                .newBuilder()
                                .setName(index),
                        ),
                    ),
                )
            if (!filter) {
                values.forEachIndexed { i, expression ->
                    projection.addExpressions(
                        NamedExpression
                            .newBuilder()
                            .setAlias(
                                "_au_value_$i",
                            ).setExpression(PlanNodeBuilder().expr(expression)),
                    )
                }
            }
            val projected = PlanNode.newBuilder().setProject(projection).build()
            return SqlTextRender.doubleQuoted(
                SqlTextRender.unqualify(
                    TranslatorFacade(IslandModelHandle(listOf(model)), SqlDialect.DUCKDB).unparse(projected),
                    "dbo",
                ),
            )
        }
        return DuckDbCombiner().use { db ->
            db.load("au_condition", staged)
            PreviewDivision.validate(
                expressions,
                rows = table.rows.size,
                render = { sqlFor(listOf(it)) },
                query = { db.query(it, emptyMap()) },
            )
            db.query(sqlFor(expressions, predicate), emptyMap()).also { result ->
                if (!predicate) {
                    if (result.rows.flatten().any { it is Double && !it.isFinite() || it is Float && !it.isFinite() }) {
                        unsupported("non-finite")
                    }
                    require(result.rows.size == table.rows.size)
                    require(
                        result.rows.map { it.first() }.toSet() ==
                            table.rows.indices
                                .map { it.toLong() }
                                .toSet(),
                    )
                }
            }
        }
    }

    private fun expressionType(type: String): TtrpType =
        TtrpType.parse(if (canonical(type) == "timestamptz") "datetime" else canonical(type))

    private fun canonical(type: String): String =
        when (type.substringBefore('(').lowercase()) {
            "integer", "bigint", "days", "working_days" -> "int"
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
                else -> unsupported("column-type")
            }
        val physical =
            if (type == "decimal") {
                val scale = values.filterIsInstance<BigDecimal>().maxOfOrNull { it.scale().coerceAtLeast(0) } ?: 0
                if (scale > 38 ||
                    values.filterIsInstance<BigDecimal>().any { it.precision() - it.scale() > 38 - scale }
                ) {
                    unsupported("decimal-range")
                }
                PhysicalType(kind, 38, scale)
            } else {
                PhysicalType(kind)
            }
        return ModelColumn(column.name, TypeMapping.surfaceType(type), physicalType = physical)
    }

    /** S2.8: [reason] is a fixed token (a diagnostic id or the check), [explanation] the compiler's text for the log. */
    private fun unsupported(
        reason: String,
        explanation: String? = null,
    ): Nothing = throw PreviewRefused(reason, explanation)
}
