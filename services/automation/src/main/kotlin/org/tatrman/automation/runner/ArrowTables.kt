// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.*
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.automation.bundle.ManifestColumn
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.channels.Channels
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Arrow IPC boundary. A decoded stream retains its schema even when every batch is empty. */
object ArrowTables {
    fun decode(
        streams: List<ByteArray>,
        expected: List<ManifestColumn> = emptyList(),
    ): Table {
        var columns: List<ManifestColumn>? = null
        val rows = mutableListOf<List<Any?>>()
        RootAllocator().use { allocator ->
            // Query workers emit self-contained IPC streams. A stream may itself hold several record batches.
            for (bytes in streams.filter { it.isNotEmpty() }) {
                ArrowStreamReader(ByteArrayInputStream(bytes), allocator).use { reader ->
                    val root = reader.vectorSchemaRoot
                    val current = root.schema.fields.map { ManifestColumn(it.name, type(it.type)) }
                    require(columns == null || current == columns) { "door changed Arrow schema mid-output" }
                    columns = current
                    while (reader.loadNextBatch()) {
                        for (row in 0 until root.rowCount) {
                            rows +=
                                root.fieldVectors.map { vector ->
                                    if (vector.isNull(row)) {
                                        null
                                    } else {
                                        when (vector) {
                                            is BaseIntVector -> (vector.getObject(row) as Number).toLong()
                                            is DecimalVector -> vector.getObject(row)
                                            is Float4Vector -> vector.get(row).toDouble()
                                            is Float8Vector -> vector.get(row)
                                            is BitVector -> vector.get(row) != 0
                                            is DateDayVector -> LocalDate.ofEpochDay(vector.get(row).toLong())
                                            is DateMilliVector ->
                                                Instant
                                                    .ofEpochMilli(
                                                        vector.get(row),
                                                    ).atOffset(ZoneOffset.UTC)
                                                    .toLocalDate()
                                            is TimeStampVector -> {
                                                val timestamp = vector.field.type as ArrowType.Timestamp
                                                val value = vector.get(row)
                                                val instant =
                                                    when (timestamp.unit) {
                                                        TimeUnit.SECOND -> Instant.ofEpochSecond(value)
                                                        TimeUnit.MILLISECOND -> Instant.ofEpochMilli(value)
                                                        TimeUnit.MICROSECOND ->
                                                            Instant.ofEpochSecond(
                                                                Math.floorDiv(value, 1_000_000L),
                                                                Math.floorMod(value, 1_000_000L) * 1000,
                                                            )
                                                        TimeUnit.NANOSECOND ->
                                                            Instant.ofEpochSecond(
                                                                Math.floorDiv(value, 1_000_000_000L),
                                                                Math.floorMod(value, 1_000_000_000L),
                                                            )
                                                    }
                                                if (timestamp.timezone ==
                                                    null
                                                ) {
                                                    LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
                                                } else {
                                                    instant.atOffset(ZoneOffset.UTC)
                                                }
                                            }
                                            else -> vector.getObject(row).toString()
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
        require(columns != null) { "door returned no Arrow schema" }
        if (expected.isNotEmpty()) {
            require(
                columns.map {
                    it.name
                } == expected.map { it.name },
            ) { "door columns differ from manifest" }
        }
        return Table(columns, rows)
    }

    fun encode(table: Table): ByteArray {
        val fields =
            table.columns.mapIndexed { i, c ->
                Field(
                    c.name,
                    FieldType.nullable(
                        when (c.type.lowercase()) {
                            "int", "integer", "bigint" -> ArrowType.Int(64, true)
                            "decimal", "numeric" -> {
                                val scale =
                                    table.rows
                                        .mapNotNull { (it[i] as? BigDecimal)?.scale() }
                                        .maxOrNull()
                                        ?.coerceAtLeast(0) ?: 0
                                ArrowType.Decimal(38, scale, 128)
                            }
                            "float", "double", "real" -> ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
                            "bool", "boolean" -> ArrowType.Bool.INSTANCE
                            "date" -> ArrowType.Date(DateUnit.DAY)
                            "timestamp", "datetime" -> ArrowType.Timestamp(TimeUnit.MICROSECOND, null)
                            "timestamptz" -> ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")
                            else -> ArrowType.Utf8.INSTANCE
                        },
                    ),
                    null,
                )
            }
        val out = ByteArrayOutputStream()
        RootAllocator().use { allocator ->
            VectorSchemaRoot.create(Schema(fields), allocator).use { root ->
                root.allocateNew()
                table.rows.forEachIndexed { row, values ->
                    values.forEachIndexed { col, value ->
                        val v = root.fieldVectors[col]
                        if (value == null) {
                            v.setNull(row)
                        } else {
                            when (v) {
                                is BigIntVector -> v.setSafe(row, (value as Number).toLong())
                                is DecimalVector ->
                                    v.setSafe(
                                        row,
                                        (value as BigDecimal).setScale((v.field.type as ArrowType.Decimal).scale),
                                    )
                                is Float8Vector -> v.setSafe(row, (value as Number).toDouble())
                                is BitVector -> v.setSafe(row, if (value as Boolean) 1 else 0)
                                is DateDayVector -> v.setSafe(row, (value as LocalDate).toEpochDay().toInt())
                                is TimeStampVector -> {
                                    val instant =
                                        when (value) {
                                            is OffsetDateTime -> value.toInstant()
                                            is Instant -> value
                                            is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
                                            else -> error("invalid timestamp")
                                        }
                                    v.setSafe(
                                        row,
                                        Math.addExact(
                                            Math.multiplyExact(instant.epochSecond, 1_000_000L),
                                            instant.nano.toLong() / 1000,
                                        ),
                                    )
                                }
                                is VarCharVector -> v.setSafe(row, value.toString().toByteArray(Charsets.UTF_8))
                                else -> error("unsupported Arrow vector ${v.javaClass.simpleName}")
                            }
                        }
                    }
                }
                root.rowCount = table.rows.size
                ArrowStreamWriter(root, null, Channels.newChannel(out)).use { writer ->
                    writer.start()
                    writer.writeBatch()
                    writer.end()
                }
            }
        }
        return out.toByteArray()
    }

    private fun type(type: ArrowType): String =
        when (type) {
            is ArrowType.Int -> "int"
            is ArrowType.Decimal -> "decimal"
            is ArrowType.FloatingPoint -> "float"
            is ArrowType.Bool -> "bool"
            is ArrowType.Date -> "date"
            is ArrowType.Timestamp -> if (type.timezone == null) "timestamp" else "timestamptz"
            is ArrowType.Utf8, is ArrowType.LargeUtf8 -> "text"
            else -> error("unsupported Arrow type $type")
        }
}
