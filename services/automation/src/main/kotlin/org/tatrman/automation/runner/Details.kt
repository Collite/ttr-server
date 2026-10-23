// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.bundle.ManifestRecipe
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.parser.model.AutoValue
import java.math.BigDecimal

/**
 * The model retains authored properties even before adding typed accessors. A route shared by several actions
 * keeps the largest required snapshot; P2's writer must apply each action's own limit when materializing it.
 * Invalid authored limits fail before the run is claimed, rather than silently selecting the default.
 */
internal fun detailLimits(
    recipe: ManifestRecipe,
    actions: Map<String, AutomationAction>,
    default: Int,
): Map<String, Int> {
    require(default > 0)
    return recipe.roles?.routes.orEmpty().filter { it.detail.isNotEmpty() }.associate { route ->
        val limits =
            recipe.wiring.filter { route.name in it.routes }.map { wiring ->
                val action = actions.getValue(wiring.action)
                val value = action.property("detail_limit")
                if (value == null) {
                    default
                } else {
                    require(value is AutoValue.Num) { "action detail_limit must be a positive integer" }
                    val limit =
                        try {
                            BigDecimal(value.text).intValueExact()
                        } catch (_: ArithmeticException) {
                            0
                        }
                    require(limit > 0) { "action detail_limit must be a positive integer" }
                    limit
                }
            }
        route.name to (limits.maxOrNull() ?: default)
    }
}

/** Compare composite keys exactly across Arrow/JDBC numeric representations; never coerce text to numbers. */
internal fun Table.keyValues(names: List<String>): List<List<Any>> {
    require(names.isNotEmpty() && names.distinct().size == names.size) { "unambiguous key columns required" }
    val indices =
        names.map { name ->
            columns.indexOfFirst { it.name == name }.also { require(it >= 0) { "key column missing" } }
        }
    return rows.map { row ->
        indices.map { index ->
            val value = requireNotNull(row[index]) { "key is null" }
            if (value is Number) BigDecimal(value.toString()).stripTrailingZeros() else value
        }
    }
}

/**
 * Keep at most limit data rows plus one marker per (route, context, key), before publishing Arrow.
 * Source/combined tables must already have passed the read ceiling: this intentional snapshot bound never
 * excuses a validator-cut context. Retained order is the immutable staged order, not a new SQL ordering.
 */
internal fun boundedDetails(
    table: Table,
    keys: List<String>,
    limit: Int,
): Table {
    require(limit > 0)
    require(table.columns.none { it.name == "truncated" }) { "reserved detail marker column" }
    val keyIndices = keys.map { name -> table.columns.indexOfFirst { it.name == name } }.toSet()
    val counts = mutableMapOf<List<Any>, Int>()
    val rows = mutableListOf<List<Any?>>()
    table.keyValues(keys).forEachIndexed { index, key ->
        val count = counts[key] ?: 0
        if (count in 0 until limit) {
            rows += table.rows[index] + false
            counts[key] = count + 1
        } else if (count == limit) {
            rows += table.rows[index].mapIndexed { column, value -> if (column in keyIndices) value else null } + true
            // Saturate at the marker; later source rows cannot grow this snapshot or overflow the counter.
            counts[key] = -1
        }
    }
    return Table(table.columns + ManifestColumn("truncated", "bool"), rows)
}
