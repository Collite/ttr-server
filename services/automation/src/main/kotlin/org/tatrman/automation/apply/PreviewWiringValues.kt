// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.api.scalar
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.RecipeBindings
import org.tatrman.automation.runner.Table
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttrp.ast.ActionsBlock
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.DurationUnit
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.parser.TtrpParser

/** Wiring data, not dispatch: partial calls and relative durations never execute or resolve a deadline. */
internal object PreviewWiringValues {
    // Mirrors RecipeChecker.WIRING_PROPERTIES until the compiler API/P3 can expose that shared contract.
    val WIRING_PROPERTIES = setOf("due", "on_due", "group_by")

    private fun wiringProperty(
        action: AutomationAction,
        name: String,
    ): String? = name.takeIf { it in WIRING_PROPERTIES && action.columns.none { column -> column.name == name } }

    internal data class Prepared(
        val action: AutomationAction,
        val sources: Map<String, String>,
        val parsed: Map<String, WiringValue>,
        val defaultDue: JsonObject?,
    )

    /** Parse pinned syntax once per wiring, independently of routes and selected rows. */
    fun prepare(
        sources: Map<String, String>,
        action: AutomationAction,
    ): Prepared =
        guarded {
            val parsed =
                sources
                    .filterKeys { name ->
                        val column = action.columns.singleOrNull { it.name == name }
                        column?.type == "action" || (wiringProperty(action, name) != null && name != "group_by")
                    }.mapValues { parse(it.value) }
            val defaultDue =
                if ("due" !in sources && action.columns.none { it.name == "due" }) {
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
            Prepared(action, sources.toMap(), parsed, defaultDue)
        }

    fun project(
        sources: Map<String, String>,
        action: AutomationAction,
        table: Table,
        bindings: Map<String, BoundValue>,
        expectedTypes: Map<String, String> = emptyMap(),
        roleNames: Set<String>,
    ): List<Map<String, JsonElement>> = project(prepare(sources, action), table, bindings, expectedTypes, roleNames)

    fun project(
        prepared: Prepared,
        table: Table,
        bindings: Map<String, BoundValue>,
        expectedTypes: Map<String, String> = emptyMap(),
        roleNames: Set<String>,
    ): List<Map<String, JsonElement>> =
        guarded {
            val action = prepared.action
            val sources = prepared.sources
            val scalars = linkedMapOf<String, String>()
            val reserved = (sources.keys + table.columns.map { it.name } + bindings.keys).toMutableSet()
            var ordinal = 0

            fun leaf(
                source: String,
                name: String? = null,
                symbolic: Boolean = true,
            ): (Map<String, JsonElement>) -> JsonElement {
                val alias = name ?: generateSequence { "_au_wiring_${ordinal++}" }.first { reserved.add(it) }
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
                        val args = node.args.associate { it.name to value(it.value, depth + 1, source) }
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
                        val column = action.columns.singleOrNull { it.name == name }
                        val property = wiringProperty(action, name)
                        val isAction = column?.type == "action" || property == "on_due"
                        val isDue = property == "due"
                        val parsed = prepared.parsed[name]
                        val expression = (parsed as? WiringValue.Expr)?.expression
                        val isNull = expression is Literal && expression.value == LiteralValue.Null
                        when (property) {
                            "due" -> require(isNull || expression is DurationLiteral || expression is DurationParam)
                            "on_due" -> require(isNull || parsed is WiringValue.Call)
                        }
                        when {
                            parsed is WiringValue.Call -> {
                                require(isAction)
                                value(parsed, 0, source)
                            }
                            parsed is WiringValue.Expr &&
                                (parsed.expression is DurationLiteral || parsed.expression is DurationParam) -> {
                                require(isDue)
                                value(parsed, 0, source)
                            }
                            else -> leaf(source, name, column == null || column.type in setOf("role", "action", "text"))
                        }
                    }.toMutableMap()
            prepared.defaultDue?.let { data -> outputs["due"] = { data } }
            val projected =
                PreviewExpressions.project(
                    scalars,
                    table,
                    bindings,
                    expectedTypes.filterKeys {
                        it in
                            scalars
                    },
                )
            projected.rows.map { values ->
                val row = projected.columns.mapIndexed { i, column -> column.name to scalar(values[i]) }.toMap()
                outputs.mapValues { it.value(row) }
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

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (_: Exception) {
            unsupported()
        } catch (_: StackOverflowError) {
            unsupported()
        }

    private const val PREFIX =
        "recipe { key: preview.id as of: now actions { " +
            "way.preview -> _preview_action { payload: "
    private val SYMBOL = Regex("[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)*")

    private fun unsupported(): Nothing =
        throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "unsupported action expression")
}
