// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorLoader
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.VectorUnloader
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.types.pojo.Schema
import java.io.ByteArrayInputStream

/**
 * BI (contracts C-5) — a worker's `ResultBatch.arrow_ipc` onto the Flight stream, buffers re-framed and values
 * untouched.
 *
 * Each worker batch is a self-contained IPC stream (schema + one record batch + EOS — the workers'
 * `ArrowIpcSerializer`), and Java Flight's listener sends a [VectorSchemaRoot], so the bytes cannot be forwarded
 * as they are (BI-0 memo §4). Each one is read with an [ArrowStreamReader] and its record batch moved into the
 * stream's root with [VectorUnloader] → [VectorLoader]: no per-value conversion, no row copy beyond Arrow's own.
 */
class BatchReframer(
    private val allocator: BufferAllocator,
) {
    /** The schema an IPC payload declares, or null for an empty payload (error and tail batches carry none). */
    fun schemaOf(ipc: ByteArray): Schema? {
        if (ipc.isEmpty()) return null
        ArrowStreamReader(ByteArrayInputStream(ipc), allocator).use { reader ->
            return reader.vectorSchemaRoot.schema
        }
    }

    /**
     * Moves every record batch of [ipc] into [target], calling [emit] after each load (the caller's `putNext`)
     * with whether that batch is the payload's last — the one a stream's closing metadata rides on. Returns the
     * rows moved. A payload with a schema and no record batch (the Polars worker's empty result) emits once, with
     * no rows, so a closing metadata riding it still goes out. A payload whose schema differs from [target]'s is
     * refused: the stream promised one schema at `GetFlightInfo`, and a second one would be read as the first.
     */
    fun reframe(
        ipc: ByteArray,
        target: VectorSchemaRoot,
        emit: (lastInPayload: Boolean) -> Unit,
    ): Long {
        if (ipc.isEmpty()) return 0
        var rows = 0L
        ArrowStreamReader(ByteArrayInputStream(ipc), allocator).use { reader ->
            val source = reader.vectorSchemaRoot
            if (source.schema != target.schema) {
                throw SchemaChangedException(expected = target.schema, actual = source.schema)
            }
            val loader = VectorLoader(target)
            var loaded = reader.loadNextBatch()
            if (!loaded) {
                target.rowCount = 0
                emit(true)
            }
            while (loaded) {
                // The unloaded batch holds its own references to the buffers, so the reader may move on to the
                // next batch (and learn whether there is one) before this one is loaded into the target.
                VectorUnloader(source).recordBatch.use { batch ->
                    loaded = reader.loadNextBatch()
                    loader.load(batch)
                }
                rows += target.rowCount
                emit(!loaded)
            }
        }
        return rows
    }
}

/** A worker batch whose schema is not the one the stream started with. */
class SchemaChangedException(
    val expected: Schema,
    val actual: Schema,
) : IllegalStateException("result schema changed mid-stream: expected $expected, got $actual")
