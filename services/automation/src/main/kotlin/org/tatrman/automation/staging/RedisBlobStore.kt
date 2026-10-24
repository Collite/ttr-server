// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.staging

import io.lettuce.core.RedisClient
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import org.tatrman.charon.core.ImmutableBlobStore
import java.nio.file.NoSuchFileException

/** Atomic SET NX, raw IPC bytes, no TTL/sidecar. Retention is exclusively the owning run's lifecycle. */
class RedisBlobStore(
    private val connection: StatefulRedisConnection<ByteArray, ByteArray>,
    private val client: RedisClient? = null,
) : ImmutableBlobStore {
    private val commands get() = connection.sync()

    override fun putIfAbsent(
        key: String,
        bytes: ByteArray,
    ): Boolean {
        if (commands.set(key.toByteArray(Charsets.UTF_8), bytes, SetArgs.Builder.nx()) == "OK") return true
        check(get(key).contentEquals(bytes)) { "a staged output cannot change within one pinned run" }
        return false
    }

    override fun get(key: String): ByteArray =
        commands.get(key.toByteArray(Charsets.UTF_8)) ?: throw NoSuchFileException("staged blob is missing")

    override fun delete(key: String) {
        commands.del(key.toByteArray(Charsets.UTF_8))
    }

    override fun deletePrefix(prefix: String) {
        require(
            prefix.isNotEmpty() && prefix.endsWith('/') && prefix.none { it in "*?[]\\" },
        ) { "invalid deletion prefix" }
        val args = ScanArgs.Builder.matches("$prefix*").limit(256)
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val page = commands.scan(cursor, args)
            val keys = page.keys
            check(keys.all { it.toString(Charsets.UTF_8).startsWith(prefix) }) { "Redis scan escaped its prefix" }
            if (keys.isNotEmpty()) commands.del(*keys.toTypedArray())
            cursor = page
        } while (!cursor.isFinished)
    }

    override fun close() {
        try {
            connection.close()
        } finally {
            client?.shutdown()
        }
    }
}
