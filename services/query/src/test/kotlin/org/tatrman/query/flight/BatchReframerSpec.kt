// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot

class BatchReframerSpec :
    StringSpec({
        val allocator = RootAllocator()
        afterSpec { allocator.close() }
        val reframer = BatchReframer(allocator)

        fun reframe(ipc: ByteArray): Pair<Long, List<Pair<Boolean, Int>>> =
            VectorSchemaRoot.create(FlightTestKit.SCHEMA, allocator).use { root ->
                val emits = mutableListOf<Pair<Boolean, Int>>()
                val rows = reframer.reframe(ipc, root) { last -> emits += last to root.rowCount }
                rows to emits
            }

        "a payload's record batch is one emit, the payload's last" {
            reframe(FlightTestKit.ipc(allocator, listOf(1 to "a", 2 to "b"))) shouldBe (2L to listOf(true to 2))
        }

        "C-5 — a zero-row batch is one emit with no rows" {
            reframe(FlightTestKit.ipc(allocator, emptyList())) shouldBe (0L to listOf(true to 0))
        }

        "C-5 — a schema with no record batch (Polars' empty result) is one emit, so a closing rides it" {
            val (rows, emits) = reframe(FlightTestKit.schemaOnly(allocator))
            rows shouldBe 0L
            emits shouldContainExactly listOf(true to 0)
        }

        "an empty payload (a tail marker) emits nothing" {
            reframe(ByteArray(0)) shouldBe (0L to emptyList())
        }
    })
