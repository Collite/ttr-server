// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.bundle.ManifestRecipe
import org.tatrman.automation.runner.Table
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.world.WorldResolution
import org.tatrman.ttr.metadata.world.WorldResolver
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.graph.model.NodeExpressions
import org.tatrman.ttrp.resolve.LoadSourceResolver
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.RecipePlacement

/** Immutable source authorization. The compiler index and source belong to the same pinned bundle. */
data class CalendarPlan internal constructor(
    val entity: String,
    val storage: String,
    val columns: List<ManifestColumn>,
    internal val index: ModelIndex,
)

internal data class CalendarSnapshot(
    val plan: CalendarPlan,
    val table: Table,
)

/** Calendar discovery is syntax/model based; neither checkout paths nor an arbitrary storage can grant scope. */
internal object PreviewCalendar {
    fun calls(expressions: List<Expression>): List<FunctionCall> =
        expressions
            .flatMap { NodeExpressions.walk(it).filterIsInstance<FunctionCall>().toList() }
            .filter { it.function.name in setOf("age", "working_days") }

    fun plan(
        bundle: Bundle,
        worldName: String,
        recipe: ManifestRecipe,
        actions: Map<String, AutomationAction>,
    ): CalendarPlan? {
        val sources =
            recipe.wiring.flatMap { wiring ->
                val action = actions.getValue(wiring.action)
                val defaults =
                    action.properties.filter { it.first == "writer_defaults" }.flatMap { (_, value) ->
                        (value as? AutoValue.Obj)?.entries.orEmpty().mapNotNull { entry ->
                            (
                                (entry.value as? AutoValue.Obj)
                                    ?.entries
                                    ?.singleOrNull {
                                        it.key == "expression"
                                    }?.value as? AutoValue.Str
                            )?.raw
                        }
                    }
                wiring.columns.values + listOfNotNull(wiring.whenCondition) + defaults
            }
        // Invalid expressions still refuse in preview. A failed parse cannot authorize an auxiliary read.
        val calls =
            calls(sources.flatMap { runCatching { PreviewWiringValues.expressions(it) }.getOrDefault(emptyList()) })
        if (calls.isEmpty()) return null
        val plan =
            bundle.prepared.calendarPlans
                .computeIfAbsent(worldName) {
                    try {
                        Result.success(source(bundle, worldName))
                    } catch (e: AutomationException) {
                        Result.failure(e)
                    }
                }.getOrThrow()
        if (plan.entity !in recipe.objectsRead) {
            throw AutomationException(ErrorCode.OUT_OF_SCOPE_READ, "calendar lookup requires declared ER scope")
        }
        if (calls.any { it.function.name == "working_days" } &&
            plan.columns.none { it.name == "working_day" && it.type in setOf("bool", "boolean") }
        ) {
            unsupported()
        }
        return plan
    }

    private fun source(
        bundle: Bundle,
        worldName: String,
    ): CalendarPlan {
        val prepared = bundle.prepared
        val index = prepared.index
        val calendar = index.calendars().singleOrNull() ?: unsupported()
        val entity = calendar.qname.dotted()
        val date = calendar.attributes.singleOrNull { it.semantics?.role == "event_date" } ?: unsupported()
        val source = LoadSourceResolver.resolve(calendar, index)?.takeUnless { it.unbound } ?: unsupported()
        val bound = source.columns.map { it.name }.toSet()
        if (date.qname.name.substringAfterLast('.') !in bound) unsupported()
        val columns =
            listOfNotNull(
                ManifestColumn(date.qname.name.substringAfterLast('.'), date.type),
                calendar.attributes
                    .singleOrNull {
                        it.qname.name.substringAfterLast('.') == "working_day" && "working_day" in bound
                    }?.let { ManifestColumn("working_day", it.type) },
            ).distinctBy { it.name }
        val declaration =
            (bundle.model.schemas["world"] as? WorldSchema)
                ?.worlds
                ?.values
                ?.singleOrNull { it.qname.name == worldName } ?: unsupported()
        val world =
            (
                WorldResolver(
                    prepared.snapshot,
                    prepared.packageOf,
                ).resolve(declaration.qname) as? WorldResolution.Ok
            )?.world
                ?: unsupported()
        val placements = RecipePlacement.storages(source, world, index)
        if (RecipePlacement.failure(source, world, placements) != null) unsupported()
        return CalendarPlan(entity, placements.single(), columns.toList(), index)
    }

    private fun unsupported(): Nothing =
        throw AutomationException(
            ErrorCode.PREVIEW_UNSUPPORTED,
            "calendar source is missing or ambiguous in the pinned world",
        )
}
