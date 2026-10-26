// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import org.tatrman.automation.bundle.ManifestRecipe
import org.tatrman.automation.bundle.ManifestDerive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.time.OffsetDateTime

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
                val result = derive(d, values)
                val value =
                    when {
                        b.type in setOf("datetime", "timestamptz") ->
                            when (val v = result.value) {
                                is LocalDate -> v.atStartOfDay().toInstant(ZoneOffset.UTC)
                                is LocalDateTime -> v.toInstant(ZoneOffset.UTC)
                                is OffsetDateTime -> v.toInstant()
                                else -> v
                            }
                        b.type == "timestamp" && result.value is LocalDate -> result.value.atStartOfDay()
                        else -> result.value
                    }
                values[b.name] = BoundValue(b.type, value)
            }
            pending.removeAll(ready.toSet())
        }
        return values.toMap()
    }

    /** Shared by manifest bindings and compiler-lowered preview date expressions; never reads a clock. */
    internal fun derive(
        d: ManifestDerive,
        values: Map<String, BoundValue>,
    ): BoundValue {
        val base = values.getValue(d.base)
        require(
            base.type in setOf("date", "datetime", "timestamp", "timestamptz"),
        ) { "date binding base is not temporal" }
        val amount =
            d.amount.toLongOrNull() ?: requireNotNull(integralAmount(values.getValue(d.amount))) {
                "derived binding amount is not an integer"
            }
        val signed =
            when (d.op) {
                "minus" -> Math.negateExact(amount)
                "plus" -> amount
                else -> error("unsupported derive operator ${d.op}")
            }
        require(
            d.unit in
                setOf(
                    "days",
                    "day",
                    "weeks",
                    "week",
                    "months",
                    "month",
                    "years",
                    "year",
                    "hours",
                    "hour",
                    "minutes",
                    "minute",
                ),
        ) {
            "unsupported derived date unit"
        }
        val resultType =
            if (base.type == "date" &&
                d.unit in setOf("minutes", "minute", "hours", "hour")
            ) {
                "timestamp"
            } else {
                base.type
            }
        if (base.value == null) return BoundValue(resultType, null)
        val date =
            when (val value = base.value) {
                is Instant -> value.atOffset(ZoneOffset.UTC)
                is LocalDate -> value.atStartOfDay().atOffset(ZoneOffset.UTC)
                is LocalDateTime -> value.atOffset(ZoneOffset.UTC)
                is OffsetDateTime -> value.withOffsetSameInstant(ZoneOffset.UTC)
                else -> error("date binding value is not temporal")
            }
        val result =
            when (d.unit) {
                "days", "day" -> date.plusDays(signed)
                "weeks", "week" -> date.plusWeeks(signed)
                "months", "month" -> date.plusMonths(signed)
                "years", "year" -> date.plusYears(signed)
                "hours", "hour" -> date.plusHours(signed)
                else -> date.plusMinutes(signed)
            }
        return BoundValue(
            resultType,
            when {
                resultType == "date" -> result.toLocalDate()
                base.value is Instant -> result.toInstant()
                base.value is LocalDate || base.value is LocalDateTime -> result.toLocalDateTime()
                else -> result
            },
        )
    }

    /** Integral counts shared by binding derivations and wiring duration evidence. */
    internal fun integralAmount(value: BoundValue): Long? =
        if (value.type in setOf("days", "working_days", "int", "integer", "bigint") &&
            (value.value is Byte || value.value is Short || value.value is Int || value.value is Long)
        ) {
            (value.value as Number).toLong()
        } else {
            null
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
