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
import org.tatrman.ttrp.graph.recipe.RecipeChecker
import org.tatrman.ttrp.resolve.TargetResolution
import java.time.Clock
import java.util.UUID

/** Pinned alongside the recipe at Open; refresh cannot replace action labels/effects or entity keys. */
data class PreviewMetadata(
    val actions: Map<String, AutomationAction>,
    val entities: List<Entity>,
    val targets: Map<String, Map<String, org.tatrman.ttrp.resolve.TargetResolution.Resolved>> = emptyMap(),
)

/** Preview synthesis over verified staged results and the runner's explicitly governed obligation snapshot. */
class ApplyPlanner(
    private val clock: Clock,
) {
    private data class DetailSnapshot(
        val table: Table,
        val byKey: Map<List<Any>, List<List<Any?>>>,
    )

    private data class PreparedWiring(
        val wiring: ManifestWiring,
        val argumentNames: Set<String>,
        val arguments: Map<Int, Map<String, JsonElement>>,
        val writer: PreviewWriter?,
        val obligation: PreviewObligation?,
        val fulfilment: PreviewFulfilment?,
        val targets: Map<String, TargetResolution.Resolved>?,
        val generated: Boolean,
    )

    private data class WiringValues(
        val defaults: PreviewWriterDefaults.Defaults,
        val values: PreviewWiringValues.Prepared,
        val obligation: PreviewObligation?,
        val fulfilment: PreviewFulfilment?,
    )

    suspend fun preview(runner: PartRunner): PreviewPlan {
        val context = runner.context
        val recipe = context.recipe.recipe
        val rows = mutableListOf<ApplyRow>()
        val actions = mutableListOf<DryRunAction>()
        val writerKeys = PreviewWriterIds.Keys()
        val obligations = PreviewObligation.Proposals()
        val fulfilments = PreviewFulfilment.Effects()
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
        val wiringValues =
            recipe.wiring.map { wiring ->
                val action = metadata.actions.getValue(wiring.action)
                val defaults = PreviewWriterDefaults.resolve(action, context)
                val scalarSources =
                    (defaults.expressions + wiring.columns).filterKeys { name ->
                        val column = action.columns.singleOrNull { it.name == name }
                        column?.type != "rows"
                    }
                val obligation =
                    if (action.sinks[Bundle.NATIVE] == "obligation" && action.transition == null) {
                        PreviewObligation.resolve(action, metadata, metadata.targets[wiring.action])
                    } else {
                        null
                    }
                val fulfilment =
                    if (action.sinks[Bundle.NATIVE] == "obligation" && action.transition != null) {
                        PreviewFulfilment.resolve(action, metadata, metadata.targets[wiring.action])
                    } else {
                        null
                    }
                WiringValues(defaults, PreviewWiringValues.prepare(scalarSources, action), obligation, fulfilment)
            }
        val roleNames = roles.contexts.map { it.name }.toSet() + listOfNotNull(roles.candidates)
        val lookupEntities = wiringValues.mapNotNull { it.fulfilment?.entity }.toSet()
        var snapshot: PreviewFulfilment.Snapshot? = null
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
                recipe.wiring
                    .withIndex()
                    .filter {
                        "$decision.${route.name}" in it.value.routes
                    }.map { (wiringIndex, wiring) ->
                        currentCoroutineContext().ensureActive()
                        val matches =
                            wiring.whenCondition?.let {
                                PreviewConditions.select(
                                    it,
                                    table,
                                    context.bindings,
                                )
                            }
                        val indices = table.rows.indices.filter { matches == null || it in matches }
                        val action = metadata.actions.getValue(wiring.action)
                        val targets = metadata.targets[wiring.action]
                        val (defaults, values, obligation, fulfilment) = wiringValues[wiringIndex]
                        // Presence wins, including explicit NULL. Defaults do not read other arguments.
                        val argumentNames =
                            (defaults.expressions.keys + wiring.columns.keys) + defaults.values.keys +
                                defaults.generated + if (values.defaultDue != null) setOf("due") else emptySet()
                        val writer =
                            if (action.sinks[Bundle.NATIVE] ==
                                "writer"
                            ) {
                                PreviewWriter.resolve(action, metadata.entities, targets)
                            } else {
                                null
                            }
                        writer?.validateArguments(assignmentNames(action, argumentNames))
                        obligation?.validateArguments(assignmentNames(action, argumentNames))
                        fulfilment?.validateArguments(assignmentNames(action, argumentNames))
                        val expectedTypes =
                            writer?.expectedTypes ?: obligation?.expectedTypes ?: fulfilment?.expectedTypes.orEmpty()
                        writer?.validateGenerated(defaults.generated)
                        require(action.columns.filterNot { it.optional }.all { it.name in wiring.columns }) {
                            "missing required action argument"
                        }
                        val contextValues =
                            PreviewExpressions
                                .constants(defaults.values, expectedTypes)
                                .filterKeys { it !in wiring.columns }
                                .mapValues { scalar(it.value) }
                        val overridden = defaults.expressions.filterKeys { it in wiring.columns }
                        if (overridden.isNotEmpty()) {
                            // Check names/types even when overridden; unused values never execute.
                            PreviewExpressions.project(
                                overridden,
                                table.copy(rows = emptyList()),
                                context.bindings,
                                expectedTypes = expectedTypes,
                            )
                        }
                        val projected =
                            PreviewWiringValues.project(
                                values,
                                table.copy(rows = indices.map { table.rows[it] }),
                                context.bindings,
                                expectedTypes = expectedTypes,
                                roleNames = roleNames,
                            )
                        PreparedWiring(
                            wiring,
                            argumentNames,
                            indices
                                .mapIndexed { i, index ->
                                    val arguments =
                                        projected[i] +
                                            contextValues +
                                            (defaults.generated - wiring.columns.keys).associateWith { column ->
                                                JsonPrimitive(
                                                    PreviewWriterIds.bigintV1(
                                                        context.id,
                                                        wiringIndex,
                                                        route.name,
                                                        column,
                                                        JsonObject(keys.zip(routeKeys[index].map(::scalar)).toMap()),
                                                    ),
                                                )
                                            }
                                    index to arguments
                                }.toMap(),
                            writer,
                            obligation,
                            fulfilment,
                            targets,
                            (defaults.generated - wiring.columns.keys).isNotEmpty(),
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
                for (prepared in selected) {
                    val wiring = prepared.wiring
                    val argumentNames = prepared.argumentNames
                    val arguments = prepared.arguments
                    val writer = prepared.writer
                    val obligation = prepared.obligation
                    val fulfilment = prepared.fulfilment
                    val targets = prepared.targets
                    val generated = prepared.generated
                    val scalarArguments = arguments[index] ?: continue
                    val action = metadata.actions.getValue(wiring.action)
                    val args = linkedMapOf<String, JsonElement>()
                    for (name in argumentNames) {
                        val column = action.columns.singleOrNull { it.name == name }
                        args[name] =
                            if (column?.type == "rows") {
                                detailArgument(
                                    wiring.columns.getValue(name),
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
                    var obligationRows: List<Int>? = null
                    when (sink) {
                        "obligation" -> {
                            val fields = assignmentNames(action, args.keys)
                            val assigned = args.filterKeys { it in fields }
                            if (fulfilment != null) {
                                val lookup =
                                    snapshot
                                        ?: PreviewFulfilment.Snapshot(runner.obligationSnapshot(lookupEntities)).also {
                                            snapshot =
                                                it
                                        }
                                obligationRows =
                                    fulfilments.add(fulfilment.transition(assigned, lookup, rows.size + 1), rows)
                            } else {
                                val insert = requireNotNull(obligation).insert(rows.size + 1, assigned, context)
                                obligationRows = listOf(obligations.add(insert, rows))
                            }
                        }
                        "transition" -> effects(action, args, metadata, targets, rows)
                        "writer" -> {
                            val fields = assignmentNames(action, args.keys)
                            val insert = requireNotNull(writer).insert(rows.size + 1, args.filterKeys { it in fields })
                            writerKeys.record(insert, generated)
                            rows += insert
                        }
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
                            obligationRows ?: (start + 1..rows.size).toList(),
                            render(action.dryRun.byLanguage["en"], args),
                        )
                }
            }
        }
        return PreviewPlan(batch, context.id.toString(), rows, actions)
    }

    internal fun effects(
        action: AutomationAction,
        args: Map<String, JsonElement>,
        metadata: PreviewMetadata,
        pinned: Map<String, TargetResolution.Resolved>?,
        rows: MutableList<ApplyRow>,
    ) {
        require(action.set != null || action.transition != null) { "transition sink has no effects" }

        fun target(ref: String): TargetResolution.Resolved {
            if (pinned != null) return pinned[ref] ?: unsupported("effect target was not pinned")
            val entity = ref.substringBeforeLast('.')
            val found =
                metadata.entities.singleOrNull { it.qname.name == entity || it.qname.dotted() == entity }
                    ?: unsupported("effect target is missing or ambiguous")
            val attribute =
                found.attributes.singleOrNull { it.qname.name.substringAfterLast('.') == ref.substringAfterLast('.') }
                    ?: unsupported("unknown effect attribute")
            return TargetResolution.Resolved(found, attribute)
        }

        fun key(entity: Entity): JsonObject {
            val columns = entity.attributes.filter { it.isKey }
            if (columns.isEmpty()) unsupported("effect target has no declared key")
            return JsonObject(
                columns.associate { attribute ->
                    val source =
                        action.columns.singleOrNull { column ->
                            if (pinned != null) {
                                pinned[column.type]?.let { it.entity == entity && it.attribute == attribute } == true
                            } else {
                                column.type == "${entity.qname.name}.${attribute.qname.name.substringAfterLast('.')}"
                            }
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
                val resolved = target(set.attribute)
                val entity = resolved.entity
                val attribute =
                    resolved.attribute.qname.name
                        .substringAfterLast('.')
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
            val resolved = target(transition.attribute)
            val entity = resolved.entity
            val attribute =
                resolved.attribute.qname.name
                    .substringAfterLast('.')
            val to =
                transition.to?.let(::JsonPrimitive) ?: args[transition.toColumn]
                    ?: unsupported("transition target is missing")
            require(to is JsonPrimitive && to.isString) { "transition target must be a state" }
            val states = resolved.attribute.states
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

    /** Undeclared wiring properties are evidence, never database assignments. Declared columns remain assignments. */
    private fun assignmentNames(
        action: AutomationAction,
        names: Set<String>,
    ): Set<String> = names - (RecipeChecker.WIRING_PROPERTIES - action.columns.map { it.name }.toSet())

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
                PreviewWiringValues.display(value)
                    ?: unsupported("dry-run text requires a scalar or deferred-call/duration evidence")
            }
        }

    private fun entityName(name: String) = if (name.startsWith("er.entity.")) name else "er.entity.$name"

    companion object {
        private val PLACEHOLDER = Regex("\\{([A-Za-z_][A-Za-z_0-9.]*)}")

        private fun unsupported(reason: String): Nothing =
            throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, reason)
    }
}
