// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * A field of a status body by dotted path: `serving.loaded_at`, `workers.0.name`. A trailing `#`
 * gives the size of the array or object at that path (`workers#`). Null when any step is missing.
 */
fun JsonElement.at(path: String): JsonElement? {
    val sizeOf = path.endsWith("#")
    val steps = path.removeSuffix("#").split('.').filter { it.isNotEmpty() }
    var node: JsonElement? = this
    for (step in steps) {
        node =
            when (val current = node) {
                is JsonObject -> current[step]
                is JsonArray -> step.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            }
        if (node == null || node is JsonNull) return null
    }
    if (!sizeOf) return node
    return when (node) {
        is JsonArray -> JsonPrimitive(node.size)
        is JsonObject -> JsonPrimitive(node.size)
        else -> null
    }
}

/** The field as text: a primitive's content, a structure's JSON. */
fun JsonElement.text(path: String): String? =
    when (val node = at(path)) {
        null -> null
        is JsonPrimitive -> node.content
        else -> node.toString()
    }

/**
 * A time as ISO-8601. Services report times as ISO strings or as epoch numbers (seconds or
 * milliseconds); anything else is passed through unchanged.
 */
fun isoTime(value: JsonElement?): String? {
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) {
        val number = primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: return primitive.content
        return if (number < 100_000_000_000L) {
            Instant.ofEpochSecond(number).toString()
        } else {
            Instant.ofEpochMilli(number).toString()
        }
    }
    val text = primitive.content
    return try {
        Instant.parse(text).toString()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant().toString()
        } catch (_: DateTimeParseException) {
            text
        }
    }
}
