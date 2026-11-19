// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import kotlinx.serialization.json.*
import org.tatrman.automation.api.scalar
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.RecipeBindings
import org.tatrman.automation.runner.Table
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttrp.ast.ActionsBlock
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationExpr
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.DurationUnit
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.graph.recipe.RecipeChecker
import org.tatrman.ttrp.parser.TtrpParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Wiring data, not dispatch: partial calls and relative durations never execute or resolve a deadline. */
internal object PreviewWiringValues {
    private val PARSED_PROPERTIES = setOf("due", "on_due")

    private fun wiringProperty(
        action: AutomationAction,
        name: String,
    ): String? =
        name.takeIf {
            it in RecipeChecker.WIRING_PROPERTIES &&
                action.columns.none { column -> column.name == name }
        }

    internal data class Prepared(
        val action: AutomationAction,
        val sources: Map<String, String>,
        val parsed: Map<String, WiringValue>,
        val defaultDue: JsonObject?,
        /**
         * S2.9a (D-249): a declared `due: date` column given a duration — the explicit value, else the action's default.
         * It is projected as the date `as_of` (in the world's zone) plus the duration, never as the duration itself.
         */
        val dueDate: DurationExpr? = null,
    ) {
        /** The argument names this preparation supplies besides the wiring's own: a defaulted `due`. */
        val suppliesDue: Boolean get() = defaultDue != null || (dueDate != null && "due" !in sources)
    }

    /** Parse pinned syntax once per wiring, independently of routes and selected rows. */
    fun prepare(
        sources: Map<String, String>,
        action: AutomationAction,
        site: PreviewSite? = null,
    ): Prepared {
        var current: String? = null
        return guarded(site, { current }) {
            val parsed =
                sources
                    .filterKeys { name ->
                        val column = action.columns.singleOrNull { it.name == name }
                        column?.type == "action" || (wiringProperty(action, name) in PARSED_PROPERTIES)
                    }.mapValues { (name, source) ->
                        current = name
                        parse(source).also { validateKinds(it, wiringProperty(action, name)) }
                    }
            current = "due"
            val defaultDue =
                if ("due" !in sources && wiringProperty(action, "due") != null) {
                    action.due?.let {
                        // MAX_VALUE rounds up to 2^63 as a Double; the upper bound must be strict.
                        require(it.amount.isFinite() && it.amount >= 0 && it.amount < Long.MAX_VALUE.toDouble())
                        require(it.amount == kotlin.math.floor(it.amount))
                        require(it.unit in DurationUnit.entries.map { unit -> unit.spelling })
                        duration(it.amount.toLong(), it.unit)
                    }
                } else {
                    null
                }
            Prepared(action, sources.toMap(), parsed, defaultDue, dueDate(sources, action))
        }
    }

    /**
     * S2.9a: a declared `due` of type `date` whose value (or, when the wiring omits it, the action's default) is a
     * duration. Working days use the governed calendar snapshot; other date units keep the compiler lowering.
     * Any other value keeps its typed date projection.
     */
    private fun dueDate(
        sources: Map<String, String>,
        action: AutomationAction,
    ): DurationExpr? {
        if (action.columns.singleOrNull { it.name == "due" }?.type != "date") return null
        val source = sources["due"]
        val duration =
            if (source != null) {
                // Not a wiring value at all: the typed projection reports it as before.
                (runCatching { parse(source) }.getOrNull() as? WiringValue.Expr)?.expression as? DurationExpr
            } else {
                action.due?.let {
                    require(it.amount.isFinite() && it.amount >= 0 && it.amount < Long.MAX_VALUE.toDouble())
                    require(it.amount == kotlin.math.floor(it.amount))
                    val unit = requireNotNull(DurationUnit.entries.singleOrNull { unit -> unit.spelling == it.unit })
                    DurationLiteral(it.amount.toLong(), unit, SourceLocation.UNKNOWN)
                }
            }
        // A date plus hours or minutes is a moment, not a due date.
        require(duration == null || duration.unit in DATE_UNITS) { "a due date requires a date duration" }
        return duration
    }

    /** Only an actual date-valued due triggers this read; deferred engine due properties stay evidence. */
    fun workingDaysDue(
        sources: Map<String, String>,
        action: AutomationAction,
    ): Boolean = runCatching { dueDate(sources, action)?.unit == DurationUnit.WORKING_DAYS }.getOrDefault(false)

    /** n = 0 and n = 1 both mean the first working day strictly after the world's local date. */
    private fun workingDay(
        after: LocalDate,
        duration: DurationExpr,
        bindings: Map<String, BoundValue>,
        calendar: CalendarSnapshot?,
    ): LocalDate {
        val amount =
            when (duration) {
                is DurationLiteral -> duration.amount
                is DurationParam -> requireNotNull(RecipeBindings.integralAmount(bindings.getValue(duration.param)))
            }
        require(amount >= 0)
        val snapshot =
            calendar
                ?: throw PreviewRefused(WORKING_DAYS_DUE, "a working-days due requires the governed calendar snapshot")
        val dateName =
            snapshot.plan.columns
                .first()
                .name
        val date = snapshot.table.columns.indexOfFirst { it.name == dateName && it.type == "date" }
        val flag =
            snapshot.table.columns.indexOfFirst {
                it.name == "working_day" && it.type in setOf("bool", "boolean")
            }
        require(date >= 0 && flag >= 0) { "calendar snapshot requires date and working_day" }
        val dates =
            snapshot.table.rows
                .filter { it[flag] == true }
                .map { it[date] as LocalDate }
                .filter { it > after }
                .sorted()
        val ordinal = maxOf(1L, amount)
        if (ordinal > dates.size.toLong()) {
            throw PreviewRefused("calendar-horizon", "the governed calendar does not reach the due day")
        }
        return dates[(ordinal - 1).toInt()]
    }

    /** `:<base> + <unit>(<count>)` — the call spelling of a duration, valid in an expression. */
    private fun dueExpression(
        base: String,
        duration: DurationExpr,
        bindings: Map<String, BoundValue>,
    ): String {
        if (duration.unit == DurationUnit.WORKING_DAYS) return ":$base"
        val count =
            when (duration) {
                is DurationLiteral -> duration.amount.toString()
                is DurationParam -> {
                    val amount = requireNotNull(RecipeBindings.integralAmount(bindings.getValue(duration.param)))
                    require(amount >= 0)
                    ":${duration.param}"
                }
            }
        return ":$base + ${duration.unit.spelling}($count)"
    }

    /** `as_of`'s calendar date in [zone]: a run instant names a moment, the due date is a day in the world. */
    private fun localDate(
        value: Any?,
        zone: ZoneId,
    ): LocalDate =
        when (value) {
            is Instant -> value.atZone(zone).toLocalDate()
            is OffsetDateTime -> value.atZoneSameInstant(zone).toLocalDate()
            is LocalDateTime -> value.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()
            is LocalDate -> value
            else -> error("as_of is not temporal")
        }

    fun project(
        prepared: Prepared,
        table: Table,
        bindings: Map<String, BoundValue>,
        expectedTypes: Map<String, String> = emptyMap(),
        roleNames: Set<String>,
        calendar: CalendarSnapshot? = null,
        site: PreviewSite? = null,
        zone: ZoneId = ZoneOffset.UTC,
    ): List<Map<String, JsonElement>> {
        var current: String? = null
        var values = bindings
        return guarded(site, { current }, { PreviewExpressions.values(table, values) }) {
            // S2.9a: a `due` duration is added to `as_of`'s local date, bound under a name no source spells:
            // a name any authored text contains is skipped, so an unknown authored reference stays unknown.
            val dueBase =
                prepared.dueDate?.let { duration ->
                    current = "due"
                    val after = localDate(bindings.getValue("as_of").value, zone)
                    val date =
                        if (duration.unit == DurationUnit.WORKING_DAYS) {
                            workingDay(after, duration, bindings, calendar)
                        } else {
                            after
                        }
                    generateSequence(0) { i -> i + 1 }
                        .map { i -> "_au_due_on_$i" }
                        .first { name ->
                            name !in bindings &&
                                table.columns.none { c -> c.name == name } &&
                                prepared.sources.values.none { source -> name in source }
                        }.also { name ->
                            values = bindings + (name to BoundValue("date", date))
                        }
                }
            current = null
            val action = prepared.action
            val sources = prepared.sources
            val scalars = linkedMapOf<String, String>()
            // S2.8: a generated alias's declared name — its argument, and the call argument it sits in.
            val declared = mutableMapOf<String, String>()
            val reserved = (sources.keys + table.columns.map { it.name } + values.keys + "due").toMutableSet()
            var ordinal = 0

            fun leaf(
                source: String,
                name: String? = null,
                symbolic: Boolean = true,
            ): (Map<String, JsonElement>) -> JsonElement {
                val alias = name ?: generateSequence { "_au_wiring_${ordinal++}" }.first { reserved.add(it) }
                if (name == null) current?.let { declared[alias] = it }
                val text = source.trim()
                scalars[alias] =
                    if (symbolic &&
                        SYMBOL.matches(text) &&
                        table.columns.none { it.name == text } &&
                        text !in setOf("true", "false", "null")
                    ) {
                        JsonPrimitive(text).toString()
                    } else {
                        source
                    }
                return { row -> row.getValue(alias) }
            }

            fun value(
                node: WiringValue,
                depth: Int,
                source: String,
            ): (Map<String, JsonElement>) -> JsonElement {
                require(depth <= 16)
                return when (node) {
                    is WiringValue.Call -> {
                        require(
                            node.args
                                .map { it.name }
                                .distinct()
                                .size == node.args.size,
                        )
                        // Until P3 pins callees, neither named capture nor a bare role reference is safe.
                        require(node.args.none { it.name in setOf("rows", "detail") })
                        val outer = current
                        val args =
                            node.args.associate {
                                current = "$outer.${it.name}"
                                it.name to value(it.value, depth + 1, source)
                            }
                        current = outer
                        return { row ->
                            buildJsonObject {
                                put("action", node.action)
                                put("arguments", JsonObject(args.mapValues { it.value(row) }))
                                put("deferred", true)
                            }
                        }
                    }
                    is WiringValue.Expr -> {
                        val expression = node.expression
                        require(
                            !(
                                depth >= 1 &&
                                    expression is ColumnRef &&
                                    expression.port == null &&
                                    expression.column in roleNames
                            ),
                        )
                        val count =
                            when (expression) {
                                is DurationLiteral -> expression.amount to expression.unit.spelling
                                is DurationParam ->
                                    requireNotNull(
                                        RecipeBindings.integralAmount(bindings.getValue(expression.param)),
                                    ) to
                                        expression.unit.spelling
                                else -> null
                            }
                        if (count == null) {
                            // Locations are ANTLR code-point offsets, not JVM UTF-16 indices.
                            val start = expression.location.offsetStart - PREFIX.length
                            val end = expression.location.offsetEnd - PREFIX.length
                            leaf(
                                source.substring(
                                    source.offsetByCodePoints(0, start),
                                    source.offsetByCodePoints(0, end),
                                ),
                            )
                        } else {
                            require(count.first >= 0)
                            val data = duration(count.first, count.second)
                            return { data }
                        }
                    }
                    is WiringValue.Values -> error("deferred lists require row/detail lowering")
                }
            }

            val outputs =
                sources
                    .mapValues { (name, source) ->
                        current = name
                        val column = action.columns.singleOrNull { it.name == name }
                        val property = wiringProperty(action, name)
                        val isAction = column?.type == "action" || property == "on_due"
                        val isDue = property == "due"
                        val parsed = prepared.parsed[name]
                        when {
                            name == "due" && prepared.dueDate != null ->
                                leaf(dueExpression(requireNotNull(dueBase), prepared.dueDate, values), name, false)
                            parsed is WiringValue.Call -> {
                                require(isAction)
                                value(parsed, 0, source)
                            }
                            parsed is WiringValue.Expr &&
                                parsed.expression is DurationExpr -> {
                                require(isDue)
                                value(parsed, 0, source)
                            }
                            else -> leaf(source, name, column == null || column.type in setOf("role", "action", "text"))
                        }
                    }.toMutableMap()
            prepared.defaultDue?.let { data -> outputs["due"] = { data } }
            if (prepared.dueDate != null && "due" !in sources) {
                current = "due"
                outputs["due"] = leaf(dueExpression(requireNotNull(dueBase), prepared.dueDate, values), "due", false)
            }
            current = null
            val projected =
                PreviewExpressions.project(
                    scalars,
                    table,
                    values,
                    expectedTypes.filterKeys {
                        it in
                            scalars
                    },
                    calendar = calendar,
                    site = site,
                    declared = declared,
                )
            projected.rows.map { values ->
                val row = projected.columns.mapIndexed { i, column -> column.name to scalar(values[i]) }.toMap()
                outputs.mapValues { it.value(row) }
            }
        }
    }

    /** Callees are not pinned yet: nested property names conservatively use the wiring contract. */
    private fun validateKinds(
        node: WiringValue,
        property: String?,
    ) {
        val expression = (node as? WiringValue.Expr)?.expression
        val isNull = expression is Literal && expression.value == LiteralValue.Null
        when (property) {
            "due" -> require(isNull || expression is DurationExpr)
            "on_due" -> require(isNull || node is WiringValue.Call)
        }
        if (node is WiringValue.Call) {
            node.args.forEach {
                validateKinds(
                    it.value,
                    it.name.takeIf { name ->
                        name in RecipeChecker.WIRING_PROPERTIES
                    },
                )
            }
        }
    }

    /** Check the full wrapper shape so no input tail is ignored. */
    private fun parse(source: String): WiringValue {
        require(source.length <= 16_384)
        val result = TtrpParser.parseString("$PREFIX$source\n } } }")
        require(result.diagnostics.none { it.severity == Severity.ERROR })
        val recipe = result.document.statements.single() as RecipeDecl
        val block = recipe.members.single() as ActionsBlock
        val wiring = block.wirings.single()
        require(wiring.action == "_preview_action" && wiring.whenCondition == null)
        require(wiring.routes.single().let { it.decision == "way" && it.route == "preview" })
        return wiring.args
            .single()
            .also { require(it.name == "payload") }
            .value
    }

    /** The same wiring parser used by preview, including expressions inside deferred calls. */
    fun expressions(source: String): List<org.tatrman.ttrp.expr.Expression> {
        fun leaves(value: WiringValue): List<org.tatrman.ttrp.expr.Expression> =
            when (value) {
                is WiringValue.Expr -> listOf(value.expression)
                is WiringValue.Call -> value.args.flatMap { leaves(it.value) }
                is WiringValue.Values -> value.items
            }
        return leaves(parse(source))
    }

    /** Unsupported placeholder shapes belong to the dry-run template diagnostic. */
    fun display(
        value: JsonElement,
        quoted: Boolean = false,
    ): String? {
        return when (value) {
            is JsonPrimitive -> if (quoted) value.toString() else value.content
            is JsonObject ->
                when {
                    value.keys ==
                        setOf(
                            "action",
                            "arguments",
                            "deferred",
                        ) &&
                        value["deferred"] == JsonPrimitive(true) -> {
                        val action = (value["action"] as? JsonPrimitive)?.content ?: return null
                        val args = value["arguments"] as? JsonObject ?: return null
                        if (args.isEmpty()) {
                            "$action {}"
                        } else {
                            val rendered =
                                args.entries.map { (key, item) ->
                                    "$key: ${display(item, true) ?: return null}"
                                }
                            "$action { ${rendered.joinToString(", ")} }"
                        }
                    }
                    value.keys == setOf("amount", "unit") -> {
                        val amount = value["amount"] as? JsonPrimitive ?: return null
                        val unit = value["unit"] as? JsonPrimitive ?: return null
                        "${amount.content} ${unit.content}"
                    }
                    else -> null
                }
            else -> null
        }
    }

    private fun duration(
        amount: Long,
        unit: String,
    ): JsonObject =
        buildJsonObject {
            put("amount", amount)
            put("unit", unit)
        }

    /** S2.8: names the wiring and the argument being built; a refusal already named inside passes through. */
    private inline fun <T> guarded(
        site: PreviewSite?,
        expression: () -> String?,
        noinline data: () -> PreviewRefusals.Redaction = { PreviewRefusals.Redaction.NONE },
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Exception) {
            PreviewRefusals.refuse(MESSAGE, site, expression(), e, data)
        } catch (e: StackOverflowError) {
            PreviewRefusals.refuse(MESSAGE, site, expression(), e)
        }

    private const val MESSAGE = "unsupported action expression"

    /** Refuse without a governed snapshot; this path never grants a calendar read itself. */
    internal const val WORKING_DAYS_DUE = "working-days-due-needs-calendar"

    private val DATE_UNITS =
        setOf(DurationUnit.DAYS, DurationUnit.WEEKS, DurationUnit.MONTHS, DurationUnit.WORKING_DAYS)

    private const val PREFIX =
        "recipe { key: preview.id as of: now actions { " +
            "way.preview -> _preview_action { payload: "
    private val SYMBOL = Regex("[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)*")
}
