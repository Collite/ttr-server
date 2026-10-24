// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.runner.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Check actual JDBC values after compiler assignment checking, before scalar JSON loses their kinds. */
internal object PreviewAssignments {
    fun assign(
        table: Table,
        expectedTypes: Map<String, String>,
    ): Table =
        Table(
            table.columns.map { it.copy(type = expectedTypes[it.name] ?: it.type) },
            table.rows.map { row ->
                row.mapIndexed { i, value ->
                    expectedTypes[table.columns[i].name]?.let { type -> assignValue(value, type) } ?: value
                }
            },
        )

    private fun assignValue(
        value: Any?,
        type: String,
    ): Any? {
        if (value == null) return null
        return when (type) {
            // A logically integer expression (notably division) can execute as DOUBLE. Even an
            // integral DOUBLE may already have lost digits; never round or recover an integer from it.
            // Explicit authored casts still work: JDBC then returns the requested exact type.
            "int" ->
                when (value) {
                    is Byte, is Short, is Int, is Long -> value
                    is BigDecimal -> value.longValueExact()
                    else -> unsupported()
                }
            "decimal" ->
                when (value) {
                    is Byte, is Short, is Int, is Long, is BigDecimal -> value
                    else -> unsupported()
                }
            "float" ->
                when (value) {
                    is Double -> value.takeIf { it.isFinite() } ?: unsupported()
                    is Float -> value.takeIf { it.isFinite() } ?: unsupported()
                    else -> unsupported()
                }
            "text" -> value as? String ?: unsupported()
            "bool" -> value as? Boolean ?: unsupported()
            "date" -> value as? LocalDate ?: unsupported()
            "timestamp" ->
                when (value) {
                    is LocalDate -> value.atStartOfDay()
                    is LocalDateTime -> value
                    else -> unsupported()
                }
            "datetime" ->
                when (value) {
                    is LocalDate -> value.atStartOfDay().atOffset(ZoneOffset.UTC)
                    // The compiler's explicit datetime cast emits a zone-less DuckDB TIMESTAMP.
                    // This scratch session is UTC; static checking has already required that cast.
                    is LocalDateTime -> value.atOffset(ZoneOffset.UTC)
                    is OffsetDateTime -> value.withOffsetSameInstant(ZoneOffset.UTC)
                    else -> unsupported()
                }
            else -> unsupported()
        }
    }

    private fun unsupported(): Nothing = error("unsupported projected assignment value")
}
