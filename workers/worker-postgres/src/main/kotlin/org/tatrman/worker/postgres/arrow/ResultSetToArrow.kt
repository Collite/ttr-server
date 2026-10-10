// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.Float4Vector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.SmallIntVector
import org.apache.arrow.vector.TimeNanoVector
import org.apache.arrow.vector.TimeStampMilliVector
import org.apache.arrow.vector.TimeStampNanoTZVector
import org.apache.arrow.vector.TimeStampNanoVector
import org.apache.arrow.vector.TimeStampSecVector
import org.apache.arrow.vector.UInt1Vector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.sql.Date
import java.sql.JDBCType
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Time
import java.sql.Timestamp

/**
 * Streams a JDBC [ResultSet] into Arrow [VectorSchemaRoot] batches sized at
 * [batchRows] rows. Engine-agnostic batcher copied from Mssql, wired to the
 * Postgres [PostgresArrowTypeMapper]. Per-cell BLOB enforcement: any binary or
 * varchar value whose serialised length exceeds [maxBlobBytesPerCell] causes the
 * row to be rejected and a `blob_too_large` warning to surface on the batch.
 *
 * The caller iterates the returned [Sequence] of [Batch]; each batch owns a fresh
 * VectorSchemaRoot the caller must close. Rejection warnings ride along on each
 * batch in which the rejection occurred.
 */
class ResultSetToArrow(
    private val allocator: BufferAllocator,
    private val batchRows: Int,
    private val maxBlobBytesPerCell: Long,
) {
    /**
     * Unconstrained numeric columns (#155 / #83): their metadata scale is "not declared", so the FIRST
     * batch sizes them. Its values for those columns are held back, the column gets the largest scale
     * they carry (capped at [PostgresArrowTypeMapper.UNCONSTRAINED_MAX_SCALE], narrower when the integer
     * part needs the room), and every later batch reuses that schema: one schema per result. A later
     * value with more decimals is rounded to it and reported in [Batch.rounded].
     */
    fun convert(resultSet: ResultSet): Sequence<Batch> {
        val meta = resultSet.metaData
        val fields = (1..meta.columnCount).map { PostgresArrowTypeMapper.fieldFor(meta, it) }
        val open =
            (1..meta.columnCount)
                .filter {
                    PostgresArrowTypeMapper.isUnconstrainedNumeric(
                        meta.getColumnTypeName(it),
                        JDBCType.valueOf(meta.getColumnType(it)),
                        meta.getPrecision(it),
                    )
                }.toSet()
        return sequence {
            var schema = Schema(fields)
            var sizing = open.isNotEmpty()
            do {
                val next = nextBatch(resultSet, meta, schema, open, sizing)
                val root = next.root
                if (sizing) {
                    schema = root.schema
                    sizing = false
                }
                if (next.rowCount == 0 && next.rejections.isEmpty()) {
                    root.close()
                    break
                }
                yield(
                    Batch(root = root, rowCount = next.rowCount, rejections = next.rejections, rounded = next.rounded),
                )
                if (next.rowCount < batchRows) break
            } while (true)
        }
    }

    private class NextBatch(
        val root: VectorSchemaRoot,
        val rowCount: Int,
        val rejections: List<RejectedRow>,
        val rounded: Map<String, Int>,
    )

    /**
     * Read up to [batchRows] rows. With [sizing], the [open] columns' values are held back instead of
     * written, and the batch is returned on a root whose open columns are sized from them.
     */
    private fun nextBatch(
        resultSet: ResultSet,
        meta: ResultSetMetaData,
        schema: Schema,
        open: Set<Int>,
        sizing: Boolean,
    ): NextBatch {
        val root = VectorSchemaRoot.create(schema, allocator)
        root.allocateNew()
        var row = 0
        val rejections = mutableListOf<RejectedRow>()
        val rounded = linkedMapOf<String, Int>()
        val held: Map<Int, MutableList<BigDecimal?>>? = if (sizing) open.associateWith { mutableListOf() } else null
        try {
            while (row < batchRows && resultSet.next()) {
                val rowHeld = if (held != null) HashMap<Int, BigDecimal?>() else null
                val rejection = appendRow(root, meta, resultSet, row, open, rowHeld, rounded)
                if (rejection != null) {
                    rejections.add(rejection)
                } else {
                    rowHeld?.forEach { (col, v) -> held!!.getValue(col).add(v) }
                    row++
                }
            }
            root.setRowCount(row)
            val out = if (held != null) sizeHeldColumns(root, held, row, meta) else root
            return NextBatch(out, row, rejections, rounded)
        } catch (t: Throwable) {
            root.close()
            throw t
        }
    }

    /**
     * Replace each held column's placeholder vector with a `Decimal(38, s)` vector, `s` the largest scale
     * its values carry, capped so the largest integer part (and never fewer than
     * [PostgresArrowTypeMapper.UNCONSTRAINED_MIN_INTEGER_DIGITS] digits) still fits.
     */
    private fun sizeHeldColumns(
        root: VectorSchemaRoot,
        held: Map<Int, List<BigDecimal?>>,
        rowCount: Int,
        meta: ResultSetMetaData,
    ): VectorSchemaRoot {
        // Every scale first: a value that cannot fit fails here, before any vector is swapped.
        val scales = held.mapValues { (col, values) -> sizedScale(root.fieldVectors[col - 1].name, values) }
        val vectors =
            root.fieldVectors.mapIndexed { i, placeholder ->
                val values = held[i + 1] ?: return@mapIndexed placeholder
                val scale = scales.getValue(i + 1)
                val field =
                    Field(
                        placeholder.name,
                        FieldType(
                            placeholder.field.isNullable,
                            ArrowType.Decimal(PostgresArrowTypeMapper.DECIMAL128_MAX_PRECISION, scale, 128),
                            null,
                            placeholder.field.metadata,
                        ),
                        emptyList(),
                    )
                placeholder.close()
                val vector = DecimalVector(field, allocator)
                vector.allocateNew(rowCount.coerceAtLeast(1))
                values.forEachIndexed { r, v ->
                    if (v == null) vector.setNull(r) else vector.setSafe(r, v.setScale(scale, RoundingMode.HALF_UP))
                }
                vector.valueCount = rowCount
                vector as FieldVector
            }
        // The placeholder root only owned the vectors carried over (closed with the new root) and the
        // placeholders closed above, so it is dropped, not closed.
        return VectorSchemaRoot(vectors.map { it.field }, vectors, rowCount)
    }

    private fun sizedScale(
        column: String,
        values: List<BigDecimal?>,
    ): Int {
        val present = values.filterNotNull()
        if (present.isEmpty()) return PostgresArrowTypeMapper.UNCONSTRAINED_MAX_SCALE
        val maxScale = present.maxOf { it.scale().coerceAtLeast(0) }
        val maxInteger = present.maxOf { integerDigits(it) }
        check(maxInteger <= PostgresArrowTypeMapper.DECIMAL128_MAX_PRECISION) {
            "Column '$column': a value with $maxInteger integer digits does not fit Decimal128 " +
                "(${PostgresArrowTypeMapper.DECIMAL128_MAX_PRECISION} digits)."
        }
        val room =
            PostgresArrowTypeMapper.DECIMAL128_MAX_PRECISION -
                maxOf(maxInteger, PostgresArrowTypeMapper.UNCONSTRAINED_MIN_INTEGER_DIGITS)
        return minOf(maxScale, room).coerceAtLeast(0)
    }

    private fun integerDigits(v: BigDecimal): Int = (v.precision() - v.scale()).coerceAtLeast(0)

    private fun appendRow(
        root: VectorSchemaRoot,
        meta: ResultSetMetaData,
        rs: ResultSet,
        row: Int,
        open: Set<Int>,
        rowHeld: MutableMap<Int, BigDecimal?>?,
        rounded: MutableMap<String, Int>,
    ): RejectedRow? {
        for (col in 1..meta.columnCount) {
            if (col in open) {
                val v = rs.getBigDecimal(col)
                if (rowHeld != null) {
                    rowHeld[col] = v
                } else {
                    setOpenDecimal(root.fieldVectors[col - 1] as DecimalVector, row, v, rounded)
                }
                continue
            }
            val vector = root.fieldVectors[col - 1]
            val rejection = setCell(vector, row, rs, col, meta)
            if (rejection != null) return rejection
        }
        return null
    }

    /** A value of an already-sized unconstrained numeric column: rounded to the column's scale, and reported if that lost digits. */
    private fun setOpenDecimal(
        vector: DecimalVector,
        row: Int,
        value: BigDecimal?,
        rounded: MutableMap<String, Int>,
    ) {
        if (value == null) {
            vector.setNull(row)
            return
        }
        val scaled = scaleFor(value, vector.scale)
        // Rounding at the cap is PostgreSQL's own precision (a quotient is already rounded there);
        // rounding below what the first batch chose is a loss the caller must hear about.
        if (vector.scale < PostgresArrowTypeMapper.UNCONSTRAINED_MAX_SCALE && scaled.compareTo(value) != 0) {
            rounded[vector.name] = vector.scale
        }
        check(scaled.precision() <= vector.precision) {
            "Column '${vector.name}': value $value needs more than the ${vector.precision - vector.scale} " +
                "integer digits Decimal128(${vector.precision}, ${vector.scale}) leaves " +
                "(${PostgresArrowTypeMapper.DECIMAL128_MAX_PRECISION} digits in all)."
        }
        vector.setSafe(row, scaled)
    }

    private fun setCell(
        vector: FieldVector,
        row: Int,
        rs: ResultSet,
        col: Int,
        meta: ResultSetMetaData,
    ): RejectedRow? {
        val typeName = meta.getColumnTypeName(col).lowercase()
        when (vector) {
            is UInt1Vector -> {
                val v = rs.getInt(col)
                if (rs.wasNull()) vector.setNull(row) else vector.setSafe(row, v)
            }
            is SmallIntVector -> {
                val v = rs.getShort(col)
                if (rs.wasNull()) vector.setNull(row) else vector.setSafe(row, v.toInt())
            }
            is IntVector -> {
                val v = rs.getInt(col)
                if (rs.wasNull()) vector.setNull(row) else vector.setSafe(row, v)
            }
            is BigIntVector -> {
                val v = rs.getLong(col)
                if (rs.wasNull()) vector.setNull(row) else vector.setSafe(row, v)
            }
            is BitVector -> {
                val v = rs.getBoolean(col)
                if (rs.wasNull()) vector.setNull(row) else vector.setSafe(row, if (v) 1 else 0)
            }
            is Float4Vector -> {
                val v = rs.getFloat(col)
                if (rs.wasNull()) vector.setNull(row) else vector.setSafe(row, v)
            }
            is Float8Vector -> {
                val v = rs.getDouble(col)
                if (rs.wasNull()) vector.setNull(row) else vector.setSafe(row, v)
            }
            is DecimalVector -> {
                val v = rs.getBigDecimal(col)
                if (v == null) {
                    vector.setNull(row)
                } else {
                    vector.setSafe(row, scaleFor(v, vector.scale))
                }
            }
            is VarCharVector -> {
                val raw = rs.getObject(col)
                if (raw == null) {
                    vector.setNull(row)
                } else {
                    val text =
                        when (raw) {
                            is String -> raw
                            else -> raw.toString()
                        }
                    val bytes = text.toByteArray(StandardCharsets.UTF_8)
                    if (bytes.size.toLong() > maxBlobBytesPerCell) {
                        return RejectedRow(typeName = typeName, sizeBytes = bytes.size.toLong())
                    }
                    vector.setSafe(row, bytes)
                }
            }
            is VarBinaryVector -> {
                val v = rs.getBytes(col)
                if (v == null) {
                    vector.setNull(row)
                } else {
                    if (v.size.toLong() > maxBlobBytesPerCell) {
                        return RejectedRow(typeName = typeName, sizeBytes = v.size.toLong())
                    }
                    vector.setSafe(row, v)
                }
            }
            is DateDayVector -> {
                val v: Date? = rs.getDate(col)
                if (v == null) {
                    vector.setNull(row)
                } else {
                    vector.setSafe(row, (v.toLocalDate().toEpochDay()).toInt())
                }
            }
            is TimeNanoVector -> {
                val v: Time? = rs.getTime(col)
                if (v == null) {
                    vector.setNull(row)
                } else {
                    val lt = v.toLocalTime()
                    vector.setSafe(row, lt.toNanoOfDay())
                }
            }
            is TimeStampSecVector -> setTimestamp(vector, row, rs.getTimestamp(col)) { it / 1_000 }
            is TimeStampMilliVector -> setTimestamp(vector, row, rs.getTimestamp(col)) { it }
            is TimeStampNanoVector ->
                setTimestamp(vector, row, rs.getTimestamp(col)) { ts ->
                    ts * 1_000_000L + nanosFraction(rs.getTimestamp(col))
                }
            is TimeStampNanoTZVector ->
                setTimestamp(vector, row, rs.getTimestamp(col)) { ts ->
                    ts * 1_000_000L + nanosFraction(rs.getTimestamp(col))
                }
            else -> {
                log.warn("Unknown vector type {} for column {}; skipping cell", vector.javaClass.simpleName, col)
                vector.setNull(row)
            }
        }
        return null
    }

    private fun <V : FieldVector> setTimestamp(
        vector: V,
        row: Int,
        ts: Timestamp?,
        encode: (Long) -> Long,
    ) {
        if (ts == null) {
            vector.setNull(row)
            return
        }
        val millis = ts.time
        when (vector) {
            is TimeStampSecVector -> vector.setSafe(row, encode(millis))
            is TimeStampMilliVector -> vector.setSafe(row, encode(millis))
            is TimeStampNanoVector -> vector.setSafe(row, encode(millis))
            is TimeStampNanoTZVector -> vector.setSafe(row, encode(millis))
        }
    }

    private fun nanosFraction(ts: Timestamp?): Long = ((ts?.nanos ?: 0) % 1_000_000L)

    private fun scaleFor(
        value: BigDecimal,
        targetScale: Int,
    ): BigDecimal =
        if (value.scale() == targetScale) {
            value
        } else {
            value.setScale(targetScale, RoundingMode.HALF_UP)
        }

    data class Batch(
        val root: VectorSchemaRoot?,
        val rowCount: Int,
        val rejections: List<RejectedRow>,
        /**
         * Unconstrained numeric columns whose values in THIS batch carried more decimals than the
         * first batch sized them to, with the scale they were rounded to (#155 / #83).
         */
        val rounded: Map<String, Int> = emptyMap(),
    )

    data class RejectedRow(
        val typeName: String,
        val sizeBytes: Long,
    )

    /**
     * The schema from the column metadata alone. An unconstrained numeric shows its widest type here
     * (`Decimal(38, 18)`); the batches [convert] yields carry it sized from the values, so a fingerprint
     * of what was SENT is taken from the first batch's root.
     */
    fun schemaOf(meta: ResultSetMetaData): Schema =
        Schema((1..meta.columnCount).map { PostgresArrowTypeMapper.fieldFor(meta, it) })

    @Suppress("unused")
    fun fieldsOf(meta: ResultSetMetaData): List<Field> =
        (1..meta.columnCount).map { PostgresArrowTypeMapper.fieldFor(meta, it) }

    companion object {
        private val log = LoggerFactory.getLogger(ResultSetToArrow::class.java)
    }
}
