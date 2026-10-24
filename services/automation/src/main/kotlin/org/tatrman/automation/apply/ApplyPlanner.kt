// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.api.scalar
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.ManifestRoute
import org.tatrman.automation.bundle.ManifestWiring
import org.tatrman.automation.runner.*
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import java.time.Clock
import java.util.UUID

/** Pinned alongside the recipe at Open; refresh cannot replace action labels/effects or entity keys. */
data class PreviewMetadata(
    val actions: Map<String, AutomationAction>,
    val entities: List<Entity>,
) {
    companion object {
        fun from(bundle: Bundle) =
            PreviewMetadata(
                bundle.actions.toMap(),
                (bundle.model.schemas["er"] as? ErSchema)
                    ?.entities
                    ?.values
                    .orEmpty()
                    .toList(),
            )
    }
}

/** Pure synthesis over verified staged results. No model connection, writer, connector or new Query reads. */
class ApplyPlanner(
    private val clock: Clock,
) {
    private data class DetailSnapshot(
        val table: Table,
        val byKey: Map<List<Any>, List<List<Any?>>>,
    )

    private data class PreparedWiring(
        val wiring: ManifestWiring,
        val arguments: Map<Int, Map<String, JsonElement>>,
    )

    suspend fun preview(runner: PartRunner): PreviewPlan {
        val context = runner.context
        val recipe = context.recipe.recipe
        val rows = mutableListOf<ApplyRow>()
        val actions = mutableListOf<DryRunAction>()
        val metadata = requireNotNull(context.previewMetadata) { "preview metadata was not pinned at open" }
        val roles = recipe.roles
        val decision = roles?.decision
        val batch = UUID.nameUUIDFromBytes("automation/preview/${context.id}".toByteArray(Charsets.UTF_8)).toString()
        if (decision == null) {
            require(recipe.wiring.isEmpty()) { "action wiring requires a decision" }
            return PreviewPlan(batch, context.id.toString(), rows, actions)
        }
        val outputs = runner.completedIsland(decision).associateBy { it.output.port }
        val tables = outputs.mapValues { (_, output) -> runner.readStaged(output) }
        val keys = recipe.key.map { it.substringAfterLast('.') }
        val schema = recipe.verdictSchema ?: unsupported("a decision requires a verdict schema")
        val names = schema.columns.map { it.getValue("name").jsonPrimitive.content }
        require(
            names.distinct().size == names.size &&
                names.containsAll(
                    keys +
                        listOf(
                            "run",
                            "as_of",
                            "route",
                            "verdict_reason",
                            "parameters_fp",
                            "run_as",
                            "written_at",
                        ),
                ),
        ) { "incomplete verdict schema" }
        val knownRoutes = roles.routes.associateBy { "$decision.${it.name}" }
        recipe.wiring.forEach { wiring ->
            require(
                wiring.routes.isNotEmpty() && wiring.routes.distinct().size == wiring.routes.size,
            ) { "invalid action routes" }
            require(wiring.routes.all { it in knownRoutes }) { "action wiring names an unknown decision route" }
            require(wiring.action in metadata.actions) { "action was not pinned at open" }
        }
        val writtenAt = clock.instant()
        for (route in roles.routes) {
            currentCoroutineContext().ensureActive()
            val table = tables.getValue(route.name)
            val routeKeys = table.keyValues(keys)
            val details =
                route.detail.associateWith {
                    val detail = tables.getValue("${route.name}.$it")
                    DetailSnapshot(detail, detail.keyValues(keys).zip(detail.rows).groupBy({ it.first }, { it.second }))
                }
            val selected =
                recipe.wiring.filter { "$decision.${route.name}" in it.routes }.map { wiring ->
                    currentCoroutineContext().ensureActive()
                    val matches = wiring.whenCondition?.let { PreviewConditions.select(it, table, context.bindings) }
                    val indices = table.rows.indices.filter { matches == null || it in matches }
                    val action = metadata.actions.getValue(wiring.action)
                    require(action.columns.filterNot { it.optional }.all { it.name in wiring.columns }) {
                        "missing required action argument"
                    }
                    val scalarExpressions =
                        wiring.columns
                            .filterKeys { name ->
                                action.columns.singleOrNull { it.name == name }?.type != "rows"
                            }.mapValues { (name, expression) ->
                                val column = action.columns.singleOrNull { it.name == name }
                                val symbolic = column == null || column.type in setOf("role", "action", "text")
                                val text = expression.trim()
                                if (symbolic &&
                                    SYMBOL.matches(text) &&
                                    table.columns.none { it.name == text } &&
                                    text !in setOf("true", "false", "null")
                                ) {
                                    JsonPrimitive(text).toString()
                                } else {
                                    expression
                                }
                            }
                    val projected =
                        PreviewExpressions.project(
                            scalarExpressions,
                            table.copy(rows = indices.map { table.rows[it] }),
                            context.bindings,
                        )
                    PreparedWiring(
                        wiring,
                        indices
                            .mapIndexed { i, index ->
                                val arguments =
                                    projected.rows[i]
                                        .mapIndexed { j, value ->
                                            projected.columns[j].name to scalar(value)
                                        }.toMap()
                                index to arguments
                            }.toMap(),
                    )
                }
            for ((index, values) in table.rows.withIndex()) {
                currentCoroutineContext().ensureActive()
                val record = table.columns.mapIndexed { i, column -> column.name to scalar(values[i]) }.toMap()
                val key = JsonObject(keys.associateWith { requireNotNull(record[it]) })
                val common =
                    mapOf(
                        "run" to scalar(context.id),
                        "as_of" to scalar(context.bindings.getValue("as_of").value),
                        "route" to scalar(route.name),
                        "parameters_fp" to scalar(recipe.fingerprints.values),
                        "run_as" to scalar(context.identity.principal),
                        "written_at" to scalar(writtenAt),
                    )
                val verdict =
                    JsonObject(
                        schema.columns.associate { column ->
                            val name = column.getValue("name").jsonPrimitive.content
                            name to (common[name] ?: record[name] ?: unsupported("unknown verdict column $name"))
                        },
                    )
                require(keys.all { key[it] != null && key[it] != JsonNull }) { "null preview key" }
                rows += ApplyRow(rows.size + 1, entityName(schema.entity), "insert", key, row = verdict)
                for ((name, detail) in details) {
                    val selected = detail.byKey[routeKeys[index]].orEmpty()
                    for ((rowNo, detailValues) in selected.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val data =
                            detail.table.columns
                                .mapIndexed { i, column ->
                                    column.name to
                                        if (column.name in keys || column.name == "truncated") {
                                            scalar(detailValues[i])
                                        } else {
                                            detailValues[i]?.let { scalar(it.toString()) } ?: JsonNull
                                        }
                                }.toMap()
                        val item =
                            JsonObject(
                                data + key +
                                    mapOf(
                                        "run" to scalar(context.id),
                                        "route" to scalar(route.name),
                                        "context" to scalar(name),
                                        "row_no" to scalar(rowNo + 1),
                                    ),
                            )
                        rows +=
                            ApplyRow(
                                rows.size + 1,
                                entityName(requireNotNull(schema.detail)),
                                "insert",
                                key,
                                row = item,
                            )
                    }
                }
                for ((wiring, arguments) in selected) {
                    val scalarArguments = arguments[index] ?: continue
                    val action = metadata.actions.getValue(wiring.action)
                    val args = linkedMapOf<String, JsonElement>()
                    for ((name, expression) in wiring.columns) {
                        val column = action.columns.singleOrNull { it.name == name }
                        args[name] =
                            if (column?.type == "rows") {
                                detailArgument(
                                    expression,
                                    details,
                                    routeKeys[index],
                                    context,
                                    route,
                                    index,
                                    actionDetailLimit(action, context.detailLimit),
                                )
                            } else {
                                scalarArguments.getValue(name)
                            }
                    }
                    val sink = action.sinks[Bundle.NATIVE] ?: unsupported("action ${wiring.action} has no native sink")
                    val start = rows.size
                    when (sink) {
                        "transition" -> effects(action, args, metadata, rows)
                        "deliver", "task", "start", "emit" -> Unit
                        else -> unsupported("native $sink preview requires its writer lowering")
                    }
                    actions +=
                        DryRunAction(
                            key,
                            route.name,
                            wiring.action,
                            sink,
                            JsonObject(args),
                            (start + 1..rows.size).toList(),
                            render(action.dryRun.byLanguage["en"], args),
                        )
                }
            }
        }
        return PreviewPlan(batch, context.id.toString(), rows, actions)
    }

    private fun effects(
        action: AutomationAction,
        args: Map<String, JsonElement>,
        metadata: PreviewMetadata,
        rows: MutableList<ApplyRow>,
    ) {
        require(action.set != null || action.transition != null) { "transition sink has no effects" }

        fun target(attribute: String): Pair<Entity, String> {
            val entity = attribute.substringBeforeLast('.')
            val found =
                metadata.entities.singleOrNull { it.qname.name == entity || it.qname.dotted() == entity }
                    ?: unsupported("effect target is missing or ambiguous")
            require(
                found.attributes.any { it.qname.name.substringAfterLast('.') == attribute.substringAfterLast('.') },
            ) { "unknown effect attribute" }
            return found to attribute.substringAfterLast('.')
        }

        fun key(entity: Entity): JsonObject {
            val columns = entity.attributes.filter { it.isKey }
            if (columns.isEmpty()) unsupported("effect target has no declared key")
            return JsonObject(
                columns.associate { attribute ->
                    val source =
                        action.columns.singleOrNull {
                            it.type ==
                                "${entity.qname.name}.${attribute.qname.name.substringAfterLast('.')}"
                        }
                            ?: unsupported("effect requires a key lookup")
                    attribute.qname.name.substringAfterLast('.') to (
                        args[source.name]?.takeUnless { it == JsonNull }
                            ?: unsupported("effect key is missing")
                    )
                },
            )
        }
        action.set?.let { set ->
            // An omitted optional argument means no assignment; an explicit null remains an assignment.
            if (set.column in args) {
                val (entity, attribute) = target(set.attribute)
                rows +=
                    ApplyRow(
                        rows.size + 1,
                        entity.qname.dotted(),
                        "update",
                        key(entity),
                        set = JsonObject(mapOf(attribute to args.getValue(set.column))),
                    )
            }
        }
        action.transition?.let { transition ->
            val (entity, attribute) = target(transition.attribute)
            val to =
                transition.to?.let(::JsonPrimitive) ?: args[transition.toColumn]
                    ?: unsupported("transition target is missing")
            require(to is JsonPrimitive && to.isString) { "transition target must be a state" }
            val states = entity.attributes.single { it.qname.name.substringAfterLast('.') == attribute }.states
            require(states.isEmpty() || to.content in states) { "unknown transition target state" }
            rows +=
                ApplyRow(
                    rows.size + 1,
                    entity.qname.dotted(),
                    "transition",
                    key(entity),
                    attribute = attribute,
                    from = transition.from,
                    to = to,
                    history = true,
                )
        }
    }

    private fun detailArgument(
        expression: String,
        details: Map<String, DetailSnapshot>,
        key: List<Any>,
        context: RunContext,
        route: ManifestRoute,
        row: Int,
        limit: Int,
    ): JsonObject {
        val refs =
            expression.split(',').map { it.trim() }.map { name ->
                val detail = details[name] ?: unsupported("action detail is not attached to its route")
                val selected = detail.byKey[key].orEmpty()
                val marker = detail.table.columns.indexOfFirst { it.name == "truncated" }
                val count = selected.count { marker < 0 || it[marker] != true }
                val truncated = count > limit || marker >= 0 && selected.any { it[marker] == true }
                buildJsonObject {
                    put("context", name)
                    put(
                        "ref",
                        "/automation/runs/${context.id}/routes/${route.name}/details/$name?row=$row",
                    )
                    put("limit", limit)
                    put("rowCount", count.coerceAtMost(limit))
                    put("truncated", truncated)
                }
            }
        return buildJsonObject {
            put("refs", JsonArray(refs))
            put("count", refs.sumOf { it.getValue("rowCount").jsonPrimitive.int })
            put("truncated", refs.any { it.getValue("truncated").jsonPrimitive.boolean })
        }
    }

    private fun render(
        template: String?,
        arguments: Map<String, JsonElement>,
    ): String? =
        template?.let {
            PLACEHOLDER.replace(it) { match ->
                var value: JsonElement = JsonObject(arguments)
                for (part in match.groupValues[1].split('.')) {
                    value = (value as? JsonObject)?.get(part)
                        ?: unsupported("unknown dry-run text argument")
                }
                if (value is JsonPrimitive) value.content else unsupported("dry-run text requires a scalar")
            }
        }

    private fun entityName(name: String) = if (name.startsWith("er.entity.")) name else "er.entity.$name"

    companion object {
        private val SYMBOL = Regex("[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)*")
        private val PLACEHOLDER = Regex("\\{([A-Za-z_][A-Za-z_0-9.]*)}")

        private fun unsupported(reason: String): Nothing =
            throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, reason)
    }
}
