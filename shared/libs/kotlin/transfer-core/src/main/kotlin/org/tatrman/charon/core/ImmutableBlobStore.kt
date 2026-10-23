// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

/**
 * Raw-byte transfer port for immutable snapshots. Unlike MoveExecutor, it does not decode/re-encode Arrow
 * or overwrite destinations. Adapters must atomically compare-and-create; a read followed by a write is
 * insufficient. Existing identical bytes return false; existing different bytes refuse without modification.
 * No implicit TTL: the owning lifecycle chooses when evidence may be released.
 */
interface ImmutableBlobStore : AutoCloseable {
    fun putIfAbsent(
        key: String,
        bytes: ByteArray,
    ): Boolean

    /** Missing objects throw; they are never interpreted as empty data. */
    fun get(key: String): ByteArray

    /** Idempotent deletion of one exact key. */
    fun delete(key: String)

    /** Delete only keys under this nonempty, slash-terminated prefix, including unjournalled blobs. */
    fun deletePrefix(prefix: String)
}
