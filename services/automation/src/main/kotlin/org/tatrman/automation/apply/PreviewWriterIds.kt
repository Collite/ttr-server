// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Versioned deterministic IDs; negative bigint space stays separate from positive database sequences. */
internal object PreviewWriterIds {
    fun bigintV1(
        run: UUID,
        wiring: Int,
        route: String,
        column: String,
        key: JsonObject,
    ): Long {
        if (wiring < 0 || key.isEmpty()) unsupported()
        val canonical = canonicalKey(key)
        val hash = MessageDigest.getInstance("SHA-256")
        for (field in listOf(
            "automation/writer/bigint_id_v1",
            run.toString(),
            wiring.toString(),
            route,
            column,
            canonical.toString(),
        )) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return ByteBuffer.wrap(hash.digest()).long or Long.MIN_VALUE
    }

    /** Check all writer inserts, so explicit overrides cannot hide a generated key collision. */
    class Keys {
        private val seen = mutableMapOf<Pair<String, JsonObject>, Boolean>()

        fun record(
            row: ApplyRow,
            generated: Boolean,
        ) {
            val previous = seen.put(row.entity to canonicalKey(row.key ?: unsupported()), generated)
            if (previous != null && (previous || generated)) unsupported()
        }
    }

    private fun canonicalKey(key: JsonObject): JsonObject {
        if (key.isEmpty()) unsupported()
        return JsonObject(
            key.toSortedMap().mapValues { (_, value) ->
                val scalar = value as? JsonPrimitive ?: unsupported()
                when {
                    scalar == JsonNull -> unsupported()
                    scalar.isString || scalar.booleanOrNull != null -> scalar
                    else -> JsonPrimitive(BigDecimal(scalar.content).stripTrailingZeros())
                }
            },
        )
    }

    private fun unsupported(): Nothing =
        throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "invalid or colliding generated writer key")
}
