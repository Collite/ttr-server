// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.LoadedRecipe
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.Entity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Validated port values; null keys means batch, an empty list means an explicitly empty selection. */
data class OpenPort(
    val keys: List<Map<String, Any?>>?,
    val eventAt: Instant?,
) {
    /** Canonical, resolved values for D-46; scalar/object key spellings and timestamp offsets are equivalent. */
    fun canonical(): JsonObject =
        buildJsonObject {
            fun row(values: Map<String, Any?>): JsonObject =
                JsonObject(
                    values.toSortedMap().mapValues { (_, value) ->
                        when (value) {
                            null -> JsonNull
                            is BigDecimal -> JsonPrimitive(value.stripTrailingZeros())
                            is Number -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            else -> JsonPrimitive(value.toString())
                        }
                    },
                )
            put(
                "keys",
                keys
                    ?.map(::row)
                    ?.distinct()
                    ?.sortedBy { it.toString() }
                    ?.let(::JsonArray) ?: JsonNull,
            )
            // D-46 retains the empty event key after the event port's grammar cut.
            put("event", JsonObject(emptyMap()))
            put("at", eventAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        }

    companion object {
        /** Grammar 0.22: the engine supplies a none/key/keys port and optional event clock. */
        fun resolve(
            bundle: Bundle,
            recipe: LoadedRecipe,
            program: AutomationProgram,
            port: JsonObject,
        ): OpenPort {
            val kind = program.port ?: "none"
            if (port.keys != setOf(kind) &&
                port.keys != setOf(kind, "at")
            ) {
                portRefused("expected exactly a $kind port and optional at")
            }
            if (kind == "none" && port.keys != setOf(kind)) portRefused("a batch port has no event clock")
            val keyTypes =
                recipe.recipe.key.associate { key ->
                    key.substringAfterLast('.') to
                        attributeType(bundle, key)
                }
            if (keyTypes.size != recipe.recipe.key.size) portRefused("recipe key column names are ambiguous")

            fun key(value: JsonElement): Map<String, Any?> {
                if (keyTypes.isEmpty()) portRefused("recipe has no key")
                val objectValue = value as? JsonObject
                val values =
                    if (objectValue !=
                        null
                    ) {
                        objectValue
                    } else if (keyTypes.size ==
                        1
                    ) {
                        JsonObject(mapOf(keyTypes.keys.single() to value))
                    } else {
                        portRefused("composite keys require every key column")
                    }
                if (values.keys != keyTypes.keys) portRefused("key must contain exactly ${keyTypes.keys}")
                return keyTypes.mapValues { (name, type) -> scalar(type, values.getValue(name)) }
            }
            val keys =
                when (kind) {
                    "none" -> {
                        // `{ none: true }` is the JSON spelling of the none port.
                        if (port[kind] != JsonPrimitive(true)) portRefused("none must be true")
                        null
                    }
                    "key" -> listOf(key(port.getValue(kind)))
                    "keys" -> (port[kind] as? JsonArray ?: portRefused("keys must be an array")).map(::key)
                    else -> portRefused("unsupported port $kind")
                }
            val at =
                port["at"]?.let { value ->
                    val text = value as? JsonPrimitive
                    if (text == null || !text.isString) portRefused("at must be an instant string")
                    try {
                        Instant.parse(text.content)
                    } catch (
                        _: java.time.DateTimeException,
                    ) {
                        portRefused("at must include a UTC offset")
                    }
                }
            return OpenPort(keys, at)
        }

        internal fun attributeType(
            bundle: Bundle,
            reference: String,
        ): String {
            val entity = reference.substringBeforeLast('.', "")
            val attribute = reference.substringAfterLast('.')
            val matches =
                bundle.model.schemas.values
                    .flatMap { it.objects().filterIsInstance<Entity>().toList() }
                    .filter { it.qname.name == entity || "${it.qname.`package`}.${it.qname.name}" == entity }
                    .flatMap { it.attributes }
                    .filter { it.qname.name.substringAfterLast('.') == attribute }
            return matches.singleOrNull()?.type ?: portRefused("key attribute $reference is missing or ambiguous")
        }

        internal fun scalar(
            type: String,
            value: JsonElement,
        ): Any {
            val p = value as? JsonPrimitive ?: portRefused("expected a $type scalar")
            if (p == JsonNull) portRefused("a key value cannot be null")
            return try {
                when (type.lowercase()) {
                    "text", "varchar", "string" -> if (p.isString) p.content else portRefused("expected a text value")
                    "int", "integer", "bigint", "smallint", "days", "working_days" ->
                        if (!p.isString) {
                            p.longOrNull
                                ?: portRefused("expected an integer")
                        } else {
                            portRefused("expected an integer")
                        }
                    "decimal", "numeric" ->
                        if (!p.isString) {
                            BigDecimal(
                                p.content,
                            )
                        } else {
                            portRefused("expected a decimal")
                        }
                    "float", "double" ->
                        if (!p.isString) {
                            p.doubleOrNull?.takeIf { it.isFinite() }
                                ?: portRefused("expected a finite number")
                        } else {
                            portRefused("expected a number")
                        }
                    "bool", "boolean" ->
                        if (!p.isString) {
                            p.booleanOrNull ?: portRefused(
                                "expected a boolean",
                            )
                        } else {
                            portRefused("expected a boolean")
                        }
                    "date" -> if (p.isString) LocalDate.parse(p.content) else portRefused("expected a date string")
                    "datetime", "timestamp", "timestamptz" ->
                        if (p.isString) {
                            Instant.parse(
                                p.content,
                            )
                        } else {
                            portRefused("expected an instant string")
                        }
                    else -> portRefused("unsupported port type $type")
                }
            } catch (
                _: IllegalArgumentException,
            ) {
                portRefused("invalid $type value")
            } catch (
                _: java.time.DateTimeException,
            ) {
                portRefused("invalid $type value")
            }
        }
    }
}

internal fun portRefused(message: String): Nothing = throw AutomationException(ErrorCode.PORT, message)
