// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.bundle.ManifestDerive
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.RecipeBindings
import org.tatrman.ttrp.ast.AsOf
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.expr.*
import org.tatrman.ttrp.graph.model.*
import org.tatrman.ttrp.graph.recipe.*

/** Adapts one bound-date operation to the compiler's graph pass; no calendar/model reads are available. */
internal object PreviewTime {
    data class Result(
        val expressions: List<Expression>,
        val bindings: Map<String, BoundValue>,
    )

    fun lower(
        expressions: List<Expression>,
        bindings: Map<String, BoundValue>,
    ): Result {
        val values = bindings.toMutableMap()
        // Check authored references before synthesizing any private binding. A generated name cannot
        // accidentally make an unknown authored parameter resolve, even in another expression.
        expressions.flatMap { NodeExpressions.walk(it).toList() }.forEach {
            when (it) {
                is ParamRef -> require(it.name in bindings) { "unknown date parameter" }
                is DurationParam ->
                    require(
                        bindings[it.param]?.type in setOf("days", "working_days", "int", "integer", "bigint"),
                    ) {
                        "duration requires an integer parameter"
                    }
                else -> Unit
            }
        }
        val lowered =
            expressions.map { expression ->
                NodeExpressions.rewrite(expression) { node ->
                    val call = node as? FunctionCall ?: return@rewrite null
                    if (call.function != CatalogId.ADD && call.function != CatalogId.SUB) return@rewrite null
                    val base = call.args.firstOrNull() as? ParamRef ?: return@rewrite null
                    if (values[base.name]?.type !in
                        setOf("date", "datetime", "timestamp", "timestamptz")
                    ) {
                        return@rewrite null
                    }
                    val rhs = call.args.getOrNull(1) ?: return@rewrite null
                    if (rhs !is DurationExpr && rhs !is ParamRef) return@rewrite null
                    val loc = call.location
                    // A source-free graph supplies the public pass's container/member context.
                    // Only this bound operation enters it; calendar/row arithmetic stays unsupported.
                    val project = Project("value", "value", loc, columns = listOf(call))
                    val container =
                        Container("preview", "preview", loc, "preview", listOf(project.id), emptyList(), emptyMap())
                    val graph =
                        TtrpGraph(
                            mapOf(project.id to project, container.id to container),
                            emptyList(),
                            mapOf(
                                container.id to container,
                            ),
                        )
                    val recipe = RecipeDecl(emptyList(), AsOf.Now, emptyList(), null, null, null, emptyList(), loc)
                    val rg = RecipeGraph(recipe, mapOf("preview" to container.id), null, null, emptyMap())
                    val parameters =
                        values.map { (name, value) ->
                            val type = if (value.type == "bigint") "int" else value.type
                            ParameterInfo(name, type, null)
                        }
                    val model =
                        RecipeModel(
                            name = "preview",
                            keyAttributes = emptyList(),
                            keyColumns = emptyList(),
                            candidates = null,
                            contexts = emptyList(),
                            decision = null,
                            routes = emptyList(),
                            switchMode = null,
                            wirings = emptyList(),
                            parameters = emptyList(),
                            mode = RecipeMode.PER_CANDIDATE,
                            programs = emptyList(),
                            parameterDecls = parameters,
                        )
                    val result = SugarLowering(rg, model, null).lower(graph)
                    require(result.diagnostics.none { it.severity == Severity.ERROR }) { "unsupported date arithmetic" }
                    val param =
                        (result.graph.nodes.getValue(project.id) as Project).columns.single() as? ParamRef
                            ?: error("date expression was not lowered")
                    val derived = result.derived.single { it.name == param.name }
                    val value =
                        RecipeBindings.derive(
                            ManifestDerive(derived.base, derived.op, derived.amount, derived.unit),
                            values,
                        )
                    val name = generateSequence(0) { it + 1 }.map { "_au_date_$it" }.first { it !in values }
                    values[name] = value
                    ParamRef(name, loc)
                }
            }
        return Result(lowered, values)
    }
}
