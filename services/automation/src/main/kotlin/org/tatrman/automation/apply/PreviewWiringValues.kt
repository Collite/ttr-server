// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.api.scalar
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.Table
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttrp.ast.ActionsBlock
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.ExprText
import org.tatrman.ttrp.parser.TtrpParser

/** Wiring data, not dispatch: partial calls and relative durations never execute or resolve a deadline. */
internal object PreviewWiringValues {
    fun project(
        sources: Map<String, String>,
        action: AutomationAction,
        table: Table,
        bindings: Map<String, BoundValue>,
        expectedTypes: Map<String, String> = emptyMap(),
    ): List<Map<String, JsonElement>> =
        guarded {
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
                        // Row/detail capture needs the later task/dispatch closure; an omitted rows parameter is partial.
                        require(node.args.none { it.name in setOf("rows", "detail") })
                        val args = node.args.associate { it.name to value(it.value, depth + 1) }
                        val result: (Map<String, JsonElement>) -> JsonElement = { row ->
                            buildJsonObject {
                                put("action", node.action)
                                put("arguments", JsonObject(args.mapValues { it.value(row) }))
                                put("deferred", true)
                            }
                        }
                        result
                    }
                    is WiringValue.Expr -> {
                        val expression = node.expression
                        val duration =
                            when (expression) {
                                is DurationLiteral -> expression.amount to expression.unit.spelling
                                is DurationParam -> {
                                    val bound = bindings.getValue(expression.param)
                                    require(bound.type in setOf("int", "integer", "bigint", "days", "working_days"))
                                    require(
                                        bound.value is Byte ||
                                            bound.value is Short ||
                                            bound.value is Int ||
                                            bound.value is Long,
                                    )
                                    (bound.value as Number).toLong() to expression.unit.spelling
                                }
                                else -> null
                            }
                        if (duration == null) {
                            leaf(ExprText.render(expression))
                        } else {
                            require(duration.first >= 0)
                            val data =
                                buildJsonObject {
                                    put("amount", duration.first)
                                    put("unit", duration.second)
                                }
                            val result: (Map<String, JsonElement>) -> JsonElement = { data }
                            result
                        }
                    }
                    is WiringValue.Values -> error("deferred lists require row/detail lowering")
                }
            }

            val outputs =
                sources.mapValues { (name, source) ->
                    val column = action.columns.singleOrNull { it.name == name }
                    val isAction = column?.type == "action" || column == null && name == "on_due"
                    val isDue = column == null && name == "due"
                    val parsed = if (isAction || isDue) parse(source) else null
                    when {
                        parsed is WiringValue.Call -> {
                            require(isAction)
                            value(parsed, 0)
                        }
                        parsed is WiringValue.Expr &&
                            (parsed.expression is DurationLiteral || parsed.expression is DurationParam) -> {
                            require(isDue)
                            value(parsed, 0)
                        }
                        else -> leaf(source, name, column == null || column.type in setOf("role", "action", "text"))
                    }
                }
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

    /** The published wiring grammar is the only parser. Check the wrapper shape so no input tail is ignored. */
    private fun parse(source: String): WiringValue {
        require(source.length <= 16_384)
        val result =
            TtrpParser.parseString(
                "recipe { key: preview.id as of: now actions { way.preview -> _preview_action { payload: $source\n } } }",
            )
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

    /** Only our two structured evidence forms can replace an authored would-text placeholder. */
    fun display(
        value: JsonElement,
        quoted: Boolean = false,
    ): String =
        when (value) {
            is JsonPrimitive -> if (quoted) value.toString() else value.content
            is JsonObject ->
                when {
                    value.keys ==
                        setOf(
                            "action",
                            "arguments",
                            "deferred",
                        ) &&
                        value["deferred"] == JsonPrimitive(true) ->
                        value.getValue("action").jsonPrimitive.content + " { " +
                            value
                                .getValue(
                                    "arguments",
                                ).jsonObject.entries
                                .joinToString(", ") { (key, item) -> "$key: ${display(item, true)}" } +
                            " }"
                    value.keys == setOf("amount", "unit") ->
                        value.getValue("amount").jsonPrimitive.content + " " +
                            value.getValue("unit").jsonPrimitive.content
                    else -> unsupported()
                }
            else -> unsupported()
        }

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (_: Exception) {
            unsupported()
        } catch (_: StackOverflowError) {
            unsupported()
        }

    private val SYMBOL = Regex("[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)*")

    private fun unsupported(): Nothing =
        throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "unsupported action expression")
}
