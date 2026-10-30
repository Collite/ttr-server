// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.tatrman.automation.apply

import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.math.BigDecimal

/** The stored key and lookup binding have exactly the same canonical text, without floating-point conversion. */
internal object ObligationKeys {
    fun canonicalText(key: JsonObject): String {
        if (key.isEmpty()) unsupported()
        return JsonObject(
            key.toSortedMap().mapValues { (_, value) ->
                val primitive = value as? JsonPrimitive ?: unsupported()
                when {
                    primitive == JsonNull -> unsupported()
                    primitive.isString || primitive.booleanOrNull != null -> primitive
                    else ->
                        try {
                            JsonUnquotedLiteral(BigDecimal(primitive.content).stripTrailingZeros().toPlainString())
                        } catch (_: NumberFormatException) {
                            unsupported()
                        }
                }
            },
        ).toString()
    }

    private fun unsupported(): Nothing =
        throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "unsupported obligation business key")
}
