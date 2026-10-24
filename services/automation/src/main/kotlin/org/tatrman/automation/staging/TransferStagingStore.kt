// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.staging

import org.tatrman.charon.core.ImmutableBlobStore
import java.util.UUID

/** Keep AU's canonical paths independent of the store's installation namespace. */
class TransferStagingStore(
    private val blobs: ImmutableBlobStore,
    private val prefix: String,
) : StagingStore {
    init {
        require(
            prefix.split('/').all { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) },
        ) { "invalid staging prefix" }
    }

    override fun putIfAbsent(
        path: String,
        bytes: ByteArray,
    ): Boolean = blobs.putIfAbsent(key(path), bytes)

    override fun get(path: String): ByteArray = blobs.get(key(path))

    override fun delete(path: String) = blobs.delete(key(path))

    override fun deleteRun(id: UUID) = blobs.deletePrefix("$prefix/runs/$id/")

    override fun close() = blobs.close()

    private fun key(path: String): String {
        val parts = path.split('/')
        require(parts.size >= 3 && parts[0] == "runs") { "invalid staged output path" }
        val id = runCatching { UUID.fromString(parts[1]) }.getOrNull()
        require(id != null && id.toString() == parts[1]) { "invalid staging run id" }
        require(
            parts.drop(2).all { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) },
        ) { "invalid staging object name" }
        return "$prefix/$path"
    }
}
