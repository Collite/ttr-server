// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.IntervalUnit
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.Schema
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.security.MessageDigest

/**
 * Serialises Arrow batches to the IPC streaming format and computes the
 * canonical-schema SHA-256 fingerprint required by `ResultBatch.schema_fingerprint`.
 *
 * The schema fingerprint is the SHA-256 (lowercase hex) of the bytes of an
 * Arrow IPC stream that contains *only* the schema header — the same prefix
 * every full IPC stream begins with. Two batches produced from the same
 * Schema therefore yield the same fingerprint regardless of row content,
 * batch index, or other batch-level differences.
 */
object ArrowIpcSerializer {
    /**
     * Returns the IPC bytes for one batch. When [includeSchema] is true, the
     * stream begins with the schema header (this is what the first
     * ResultBatch in a query carries); when false, only the record-batch
     * frames travel — used by subsequent batches in a Worker stream.
     *
     * v1 implementation always emits the schema-prefixed form for every
     * batch. The streaming protocol allows it; consumers parse the stream
     * forwards regardless. Optimising to omit the schema on later batches
     * is a v1.5+ refinement.
     */
    fun serializeBatch(root: VectorSchemaRoot): ByteArray {
        val out = ByteArrayOutputStream()
        ArrowStreamWriter(root, null, Channels.newChannel(out)).use { writer ->
            writer.start()
            writer.writeBatch()
            writer.end()
        }
        return out.toByteArray()
    }

    /**
     * A zero-row batch of [schema], in the same form: what the tail of an empty result carries, so a consumer
     * learns the columns of an answer with no rows (BI C-5).
     */
    fun serializeEmpty(
        schema: Schema,
        allocator: BufferAllocator,
    ): ByteArray =
        VectorSchemaRoot.create(schema, allocator).use { root ->
            root.allocateNew()
            root.rowCount = 0
            serializeBatch(root)
        }

    /**
     * Cross-engine schema fingerprint = SHA-256 over the **canonical,
     * implementation-independent** logical-schema string (fork Stage 3.4 T2,
     * review-006 R3). NOT the raw IPC `serializeAsMessage()` bytes — those are
     * not byte-stable across Arrow implementations/versions, so Mssql (Arrow
     * Java) and Polars (pyarrow) would have disagreed for the same schema.
     *
     * The algorithm is byte-identical to Charon's `Integrity.canonicalSchemaString`
     * and the Python reference; the shared fixture set
     * (`shared/testdata/fingerprints/`) pins all implementations together
     * (`SchemaFingerprintCrossEngineSpec`).
     */
    fun fingerprintFor(schema: Schema): String {
        val canonical = canonicalSchemaString(schema)
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** The canonical logical-schema string (exposed for the cross-engine cross-check). */
    fun canonicalSchemaString(schema: Schema): String = schema.fields.joinToString(separator = "\n") { encodeField(it) }

    private fun encodeField(field: Field): String {
        val nullability = if (field.isNullable) "null" else "nonnull"
        val children =
            if (field.children.isNullOrEmpty()) {
                ""
            } else {
                "<" + field.children.joinToString(";") { encodeField(it) } + ">"
            }
        return field.name + "|" + encodeType(field.type) + "|" + nullability + children
    }

    private fun encodeType(type: ArrowType): String =
        when (type) {
            is ArrowType.Null -> "null"
            is ArrowType.Bool -> "bool"
            is ArrowType.Int -> "int" + type.bitWidth + if (type.isSigned) "s" else "u"
            is ArrowType.FloatingPoint -> "float" + fpToken(type.precision)
            is ArrowType.Decimal -> "decimal" + type.bitWidth + "_" + type.precision + "_" + type.scale
            is ArrowType.Utf8 -> "utf8"
            is ArrowType.LargeUtf8 -> "large_utf8"
            is ArrowType.Binary -> "binary"
            is ArrowType.LargeBinary -> "large_binary"
            is ArrowType.FixedSizeBinary -> "fixed_size_binary_" + type.byteWidth
            is ArrowType.Date -> "date_" + dateToken(type.unit)
            is ArrowType.Time -> "time_" + timeToken(type.unit) + "_" + type.bitWidth
            is ArrowType.Timestamp -> "timestamp_" + timeToken(type.unit) + "_" + (type.timezone ?: "")
            is ArrowType.Duration -> "duration_" + timeToken(type.unit)
            is ArrowType.Interval -> "interval_" + intervalToken(type.unit)
            is ArrowType.List -> "list"
            is ArrowType.LargeList -> "large_list"
            is ArrowType.FixedSizeList -> "fixed_size_list_" + type.listSize
            is ArrowType.Struct -> "struct"
            is ArrowType.Map -> "map_" + if (type.keysSorted) "sorted" else "unsorted"
            else -> error("Mssql fingerprint: unsupported Arrow type $type")
        }

    private fun fpToken(p: FloatingPointPrecision): String =
        when (p) {
            FloatingPointPrecision.HALF -> "16"
            FloatingPointPrecision.SINGLE -> "32"
            FloatingPointPrecision.DOUBLE -> "64"
        }

    private fun timeToken(u: TimeUnit): String =
        when (u) {
            TimeUnit.SECOND -> "s"
            TimeUnit.MILLISECOND -> "ms"
            TimeUnit.MICROSECOND -> "us"
            TimeUnit.NANOSECOND -> "ns"
        }

    private fun dateToken(u: DateUnit): String =
        when (u) {
            DateUnit.DAY -> "day"
            DateUnit.MILLISECOND -> "ms"
        }

    private fun intervalToken(u: IntervalUnit): String =
        when (u) {
            IntervalUnit.YEAR_MONTH -> "ym"
            IntervalUnit.DAY_TIME -> "dt"
            IntervalUnit.MONTH_DAY_NANO -> "mdn"
        }
}
