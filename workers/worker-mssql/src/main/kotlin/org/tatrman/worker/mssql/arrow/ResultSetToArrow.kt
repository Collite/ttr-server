// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.arrow

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
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.Schema
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.sql.Date
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Time
import java.sql.Timestamp

/**
 * Streams a JDBC [ResultSet] into Arrow [VectorSchemaRoot] batches sized at
 * [batchRows] rows. Per-cell BLOB enforcement: any binary or varchar value
 * whose serialised length exceeds [maxBlobBytesPerCell] causes the row to
 * be rejected; the offending row is skipped and a `blob_too_large` warning
 * surfaces through [Result].
 *
 * The caller iterates the returned [Sequence] of [Batch]; each batch owns
 * a fresh VectorSchemaRoot that the caller is responsible for closing
 * (typically via `use { ... }` in `try-with-resources` style). Rejection
 * warnings ride along on each batch in which the rejection occurred.
 */
class ResultSetToArrow(
    private val allocator: BufferAllocator,
    private val batchRows: Int,
    private val maxBlobBytesPerCell: Long,
) {
    /**
     * [rowLimit] (`ExecutionOptions.row_limit`, 0 = none) bounds the rows read across all batches: the last batch
     * stops at it, however [batchRows] falls (BI C-10).
     */
    fun convert(
        resultSet: ResultSet,
        rowLimit: Long = 0,
    ): Sequence<Batch> {
        val meta = resultSet.metaData
        val fields = (1..meta.columnCount).map { MssqlArrowTypeMapper.fieldFor(meta, it) }
        val schema = Schema(fields)
        return sequence {
            var remaining = if (rowLimit > 0) rowLimit else Long.MAX_VALUE
            do {
                val want = minOf(batchRows.toLong(), remaining).toInt()
                val (root, rowCount, rejections) = nextBatch(resultSet, meta, schema, want)
                if (rowCount == 0 && rejections.isEmpty() && root != null) {
                    root.close()
                    break
                }
                yield(Batch(root = root, rowCount = rowCount, rejections = rejections))
                remaining -= rowCount
                if (rowCount < want || remaining <= 0) break
            } while (true)
        }
    }

    private fun nextBatch(
        resultSet: ResultSet,
        meta: ResultSetMetaData,
        schema: Schema,
        rows: Int,
    ): Triple<VectorSchemaRoot?, Int, List<RejectedRow>> {
        val root = VectorSchemaRoot.create(schema, allocator)
        root.allocateNew()
        var row = 0
        val rejections = mutableListOf<RejectedRow>()
        try {
            while (row < rows && resultSet.next()) {
                val rejection = appendRow(root, meta, resultSet, row)
                if (rejection != null) {
                    rejections.add(rejection)
                } else {
                    row++
                }
            }
            root.setRowCount(row)
            return Triple(root, row, rejections)
        } catch (t: Throwable) {
            root.close()
            throw t
        }
    }

    private fun appendRow(
        root: VectorSchemaRoot,
        meta: ResultSetMetaData,
        rs: ResultSet,
        row: Int,
    ): RejectedRow? {
        for (col in 1..meta.columnCount) {
            val vector = root.fieldVectors[col - 1]
            val rejection = setCell(vector, row, rs, col, meta)
            if (rejection != null) return rejection
        }
        return null
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
    )

    data class RejectedRow(
        val typeName: String,
        val sizeBytes: Long,
    )

    /** Convenience: pull every column metadata field once, e.g. for the schema fingerprint. */
    fun schemaOf(meta: ResultSetMetaData): Schema =
        Schema((1..meta.columnCount).map { MssqlArrowTypeMapper.fieldFor(meta, it) })

    @Suppress("unused")
    fun fieldsOf(meta: ResultSetMetaData): List<Field> =
        (1..meta.columnCount).map { MssqlArrowTypeMapper.fieldFor(meta, it) }

    companion object {
        private val log = LoggerFactory.getLogger(ResultSetToArrow::class.java)
    }
}
