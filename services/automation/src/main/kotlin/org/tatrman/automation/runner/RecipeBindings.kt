// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import org.tatrman.automation.bundle.ManifestRecipe
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** AU-C-4: resolve values at open; Query binds scalars and never computes recipe date arithmetic. */
object RecipeBindings {
    fun resolve(
        recipe: ManifestRecipe,
        asOf: Instant,
        overrides: Map<String, BoundValue> = emptyMap(),
    ): Map<String, BoundValue> {
        require(
            overrides.keys.all { name ->
                recipe.parameters.any { it.name == name }
            },
        ) { "unknown parameter override" }
        val values =
            recipe.parameters
                .associate { p ->
                    p.name to (
                        overrides[p.name]?.also { require(it.type == p.type) { "override type differs for ${p.name}" } }
                            ?: BoundValue(
                                p.type,
                                literal(p.type, requireNotNull(p.value) { "parameter ${p.name} has no value" }),
                            )
                    )
                }.toMutableMap()
        require("as_of" !in values) { "as_of is reserved" }
        values["as_of"] = BoundValue("datetime", asOf)
        val pending = recipe.bindings.filter { it.source != "run" || it.name != "as_of" }.toMutableList()
        require(
            pending
                .map {
                    it.name
                }.distinct()
                .size == pending.size &&
                pending.none { it.name in values },
        ) { "duplicate recipe bindings" }
        while (pending.isNotEmpty()) {
            val ready = pending.filter { b -> b.derive?.base in values }
            require(ready.isNotEmpty()) { "unknown or cyclic derived recipe binding" }
            for (b in ready) {
                require(b.source == "derived") { "unknown binding source ${b.source}" }
                val d = requireNotNull(b.derive)
                val base = values.getValue(d.base).value as? Instant ?: error("date binding base is not an instant")
                val amount =
                    d.amount.toLongOrNull() ?: (values[d.amount]?.value as? Number)?.toLong()
                        ?: error("derived binding amount is not an integer")
                val signed =
                    when (d.op) {
                        "minus" -> Math.negateExact(amount)
                        "plus" -> amount
                        else -> error("unsupported derive operator ${d.op}")
                    }
                val date = base.atOffset(ZoneOffset.UTC)
                val result =
                    when (d.unit) {
                        "days", "day" -> date.plusDays(signed)
                        "weeks", "week" -> date.plusWeeks(signed)
                        "months", "month" -> date.plusMonths(signed)
                        "years", "year" -> date.plusYears(signed)
                        "hours", "hour" -> date.plusHours(signed)
                        else -> error("unsupported derived date unit ${d.unit}")
                    }.toInstant()
                values[b.name] = BoundValue(b.type, result)
            }
            pending.removeAll(ready.toSet())
        }
        return values.toMap()
    }

    private fun literal(
        type: String,
        value: String,
    ): Any =
        when (type) {
            "int", "integer", "bigint", "days", "working_days" -> value.toLong()
            "decimal", "numeric" -> BigDecimal(value)
            "float", "double" -> value.toDouble()
            "bool", "boolean" -> value.toBooleanStrict()
            "date" -> LocalDate.parse(value.removeSurrounding("\""))
            "datetime", "timestamp" -> Instant.parse(value.removeSurrounding("\""))
            "text", "varchar" ->
                kotlinx.serialization.json.Json
                    .decodeFromString<String>(value)
            else -> error("unsupported recipe parameter type $type")
        }
}
