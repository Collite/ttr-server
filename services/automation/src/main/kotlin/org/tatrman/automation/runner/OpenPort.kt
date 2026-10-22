// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.LoadedRecipe
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.RowSchemaRecord
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Validated port values; null keys means batch, an empty list means an explicitly empty selection. */
data class OpenPort(
    val keys: List<Map<String, Any?>>?,
    val eventAt: Instant?,
    val eventValues: Map<String, Any?> = emptyMap(),
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
            put("event", row(eventValues))
            put("at", eventAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        }

    companion object {
        /** D-104: the event schema is resolved in the program's package, never by an ambiguous bare-name match. */
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
            val eventValues = mutableMapOf<String, Any?>()

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
                    "event" -> {
                        val event = port[kind] as? JsonObject ?: portRefused("event must be an object")
                        val schema = eventSchema(bundle, program)
                        if ((event.keys - schema.columns.map { it.name }.toSet()).isNotEmpty()) {
                            portRefused(
                                "event contains undeclared columns",
                            )
                        }
                        for (column in schema.columns) {
                            val value = event[column.name]
                            if (value == null || value == JsonNull) {
                                if (!column.optional) portRefused("event requires ${column.name}")
                            } else {
                                eventValues[column.name] = scalar(resolveType(bundle, column.type), value)
                            }
                        }
                        listOf(
                            keyTypes.mapValues { (name, type) ->
                                val keyRef = recipe.recipe.key.single { it.substringAfterLast('.') == name }
                                val column =
                                    schema.columns.singleOrNull {
                                        it.type == keyRef ||
                                            (it.name == name && resolveType(bundle, it.type) == type)
                                    }
                                        ?: portRefused("event schema must identify key $keyRef")
                                scalar(type, event[column.name] ?: portRefused("event requires key ${column.name}"))
                            },
                        )
                    }
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
            return OpenPort(keys, at, eventValues.toMap())
        }

        private fun eventSchema(
            bundle: Bundle,
            program: AutomationProgram,
        ): RowSchemaRecord {
            val name = program.event ?: portRefused("event program has no schema")
            return bundle.model.rowSchemas["${program.qname.`package`}.$name"]
                ?: bundle.model.rowSchemas[name]
                ?: portRefused("event schema $name is not available in the program package")
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

        private fun resolveType(
            bundle: Bundle,
            type: String,
        ): String =
            if ('.' in
                type
            ) {
                attributeType(bundle, type)
            } else {
                type
            }

        internal fun scalar(
            type: String,
            value: JsonElement,
        ): Any {
            val p = value as? JsonPrimitive ?: portRefused("expected a $type scalar")
            if (p == JsonNull) portRefused("a key or required event value cannot be null")
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
