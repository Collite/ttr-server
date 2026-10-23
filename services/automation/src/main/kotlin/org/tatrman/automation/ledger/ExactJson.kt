// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import kotlinx.serialization.json.*

/** JSONB preserves numeric values but may normalize exponent/scale spelling. Never compare through Double. */
internal fun sameJson(
    a: JsonElement?,
    b: JsonElement?,
): Boolean =
    when {
        a == null || b == null -> a == b
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> sameJson(value, b[key]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive ->
            when {
                a == JsonNull || b == JsonNull -> a == b
                a.isString || b.isString || a.booleanOrNull != null || b.booleanOrNull != null -> a == b
                else ->
                    a.content.toBigDecimalOrNull()?.let { x ->
                        b.content.toBigDecimalOrNull()?.let {
                            x.compareTo(it) ==
                                0
                        }
                    } ==
                        true
            }
        else -> false
    }
