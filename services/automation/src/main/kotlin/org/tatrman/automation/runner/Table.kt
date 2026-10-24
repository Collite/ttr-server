// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import org.tatrman.automation.bundle.ManifestColumn
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Immutable, typed rows. The schema exists even for zero rows and all-null columns. */
data class Table(
    val columns: List<ManifestColumn>,
    val rows: List<List<Any?>>,
) {
    init {
        require(columns.map { it.name }.distinct().size == columns.size)
        require(rows.all { it.size == columns.size })
    }
}

data class BoundValue(
    val type: String,
    val value: Any?,
)

fun quoted(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/** Prepared JDBC only; no values are interpolated into SQL. */
object JdbcTables {
    fun query(
        connection: Connection,
        sql: String,
        bindings: Map<String, BoundValue>,
    ): Table {
        val values = mutableListOf<Any?>()
        val prepared =
            SqlParameters.rewrite(sql) { name ->
                val value = requireNotNull(bindings[name]) { "unbound parameter $name" }
                values += value.value
                "?"
            }
        connection.prepareStatement(prepared).use { st ->
            values.forEachIndexed { i, value ->
                st.setObject(i + 1, if (value is java.time.Instant) value.atOffset(java.time.ZoneOffset.UTC) else value)
            }
            st.executeQuery().use { rs ->
                val md = rs.metaData
                val columns =
                    (1..md.columnCount).map { i ->
                        ManifestColumn(
                            md.getColumnLabel(i),
                            if (md.getColumnTypeName(i).equals("HUGEINT", ignoreCase = true)) {
                                "decimal" // Preserve DuckDB's 128-bit integer SUM.
                            } else {
                                when (md.getColumnType(i)) {
                                    Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "int"
                                    Types.NUMERIC, Types.DECIMAL -> "decimal"
                                    Types.REAL, Types.FLOAT, Types.DOUBLE -> "float"
                                    Types.BIT, Types.BOOLEAN -> "bool"
                                    Types.DATE -> "date"
                                    Types.TIMESTAMP_WITH_TIMEZONE -> "timestamptz"
                                    Types.TIMESTAMP -> "timestamp"
                                    else -> "text"
                                }
                            },
                        )
                    }
                val rows = mutableListOf<List<Any?>>()
                while (rs.next()) {
                    rows +=
                        columns.mapIndexed { i, c ->
                            val value =
                                when (c.type) {
                                    "int" -> rs.getLong(i + 1)
                                    "decimal" -> rs.getBigDecimal(i + 1)
                                    "float" -> rs.getDouble(i + 1)
                                    "bool" -> rs.getBoolean(i + 1)
                                    "date" -> rs.getObject(i + 1, LocalDate::class.java)
                                    "timestamp" -> rs.getObject(i + 1, LocalDateTime::class.java)
                                    "timestamptz" ->
                                        rs
                                            .getObject(
                                                i + 1,
                                                OffsetDateTime::class.java,
                                            )?.withOffsetSameInstant(java.time.ZoneOffset.UTC)
                                    else -> rs.getString(i + 1)
                                }
                            if (rs.wasNull()) null else value
                        }
                }
                return Table(columns, rows)
            }
        }
    }
}

/** One in-memory database per run. It contains only governed door results. */
class DuckDbCombiner : AutoCloseable {
    private val connection =
        java.sql.DriverManager.getConnection("jdbc:duckdb:").also { db ->
            db.createStatement().use { it.execute("SET TimeZone = 'UTC'") }
        }

    @Synchronized fun load(
        name: String,
        table: Table,
    ) {
        val ddl =
            table.columns.mapIndexed { i, c ->
                quoted(c.name) + " " +
                    when (c.type.lowercase()) {
                        "int", "integer", "bigint" -> "BIGINT"
                        "decimal", "numeric" -> {
                            val scale =
                                table.rows
                                    .mapNotNull { (it[i] as? BigDecimal)?.scale() }
                                    .maxOrNull()
                                    ?.coerceAtLeast(0) ?: 0
                            require(scale <= 38) { "decimal scale exceeds DuckDB precision" }
                            "DECIMAL(38,$scale)"
                        }
                        "float", "double", "real" -> "DOUBLE"
                        "bool", "boolean" -> "BOOLEAN"
                        "date" -> "DATE"
                        "timestamp", "datetime" -> "TIMESTAMP"
                        "timestamptz" -> "TIMESTAMPTZ"
                        else -> "VARCHAR"
                    }
            }
        connection.createStatement().use { it.execute("CREATE TABLE ${quoted(name)} (${ddl.joinToString()})") }
        if (table.rows.isEmpty()) return
        val insert = "INSERT INTO ${quoted(name)} VALUES (${table.columns.joinToString { "?" }})"
        connection.prepareStatement(insert).use { st ->
            table.rows.forEach { row ->
                row.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    @Synchronized fun query(
        sql: String,
        bindings: Map<String, BoundValue>,
    ): Table = JdbcTables.query(connection, sql, bindings)

    override fun close() {
        connection.close()
    }
}
