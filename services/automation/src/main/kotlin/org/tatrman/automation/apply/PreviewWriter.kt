// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttrp.resolve.ColumnType
import java.math.BigDecimal

/** Explicit action-column → ER attribute insert mapping. No generated/default values or model reads. */
internal class PreviewWriter private constructor(
    private val entity: Entity,
    private val attributes: Map<String, Attribute>,
    private val required: Set<String>,
) {
    val expectedTypes: Map<String, String> =
        attributes.mapValues { (_, attribute) ->
            when (ColumnType.parse(attribute.type)?.kind) {
                ColumnType.Kind.INT, ColumnType.Kind.BIGINT, ColumnType.Kind.SMALLINT, ColumnType.Kind.TINYINT -> "int"
                ColumnType.Kind.DECIMAL -> "decimal"
                ColumnType.Kind.FLOAT, ColumnType.Kind.REAL -> "float"
                ColumnType.Kind.TEXT -> "text"
                ColumnType.Kind.BOOL -> "bool"
                ColumnType.Kind.DATE -> "date"
                ColumnType.Kind.TIMESTAMP -> "timestamp"
                ColumnType.Kind.DATETIME -> "datetime"
                else -> unsupported()
            }
        }

    fun validateArguments(names: Set<String>) {
        if (!attributes.keys.containsAll(names) || !names.containsAll(required)) unsupported()
    }

    fun insert(
        seq: Int,
        arguments: Map<String, JsonElement>,
    ): ApplyRow {
        validateArguments(arguments.keys)
        val row = linkedMapOf<String, JsonElement>()
        for ((argument, attribute) in attributes) {
            val value = arguments[argument]
            if (value == null) {
                if (attribute.isKey || !attribute.nullable) unsupported()
                continue // An omitted optional field is not an explicit NULL assignment.
            }
            if (value == JsonNull) {
                if (attribute.isKey || !attribute.nullable) unsupported()
            } else {
                if (value !is JsonPrimitive) unsupported()
                if (attribute.states.isNotEmpty() &&
                    (!value.isString || value.content !in attribute.states)
                ) {
                    unsupported()
                }
                // The compiler checks logical assignment types before projection. Check declared
                // decimal precision/scale and text bounds without casting/rounding/truncating data.
                // Attribute metadata can lose structured precision/length. Never infer a bound
                // from ColumnType's bare-decimal default; P2 must validate the physical target.
                if ('(' in attribute.type || '{' in attribute.type) {
                    ColumnType.parse(attribute.type)?.let { type ->
                        when (type.kind) {
                            ColumnType.Kind.DECIMAL -> {
                                val number = BigDecimal(value.content).stripTrailingZeros()
                                val scale = type.scale ?: 0
                                val precision = type.precision ?: error("missing decimal precision")
                                val integerDigits = if (number.signum() == 0) 0 else number.precision() - number.scale()
                                if (number.scale().coerceAtLeast(0) > scale ||
                                    integerDigits > precision - scale
                                ) {
                                    unsupported()
                                }
                            }
                            ColumnType.Kind.TEXT ->
                                type.length?.let { max ->
                                    if (value.content.codePointCount(0, value.content.length) > max) unsupported()
                                }
                            else -> Unit
                        }
                    }
                }
            }
            row[attribute.qname.name.substringAfterLast('.')] = value
        }
        val key =
            entity.attributes.filter { it.isKey }.associate { attribute ->
                val name = attribute.qname.name.substringAfterLast('.')
                name to (row[name]?.takeUnless { it == JsonNull } ?: unsupported())
            }
        return ApplyRow(seq, entity.qname.dotted(), "insert", JsonObject(key), row = JsonObject(row))
    }

    companion object {
        fun resolve(
            action: AutomationAction,
            entities: List<Entity>,
        ): PreviewWriter {
            if (action.columns.isEmpty() || action.set != null || action.transition != null) unsupported()
            if (action.columns
                    .map { it.name }
                    .distinct()
                    .size != action.columns.size
            ) {
                unsupported()
            }
            val targets =
                action.columns.map { column ->
                    val entityName = column.type.substringBeforeLast('.', "")
                    val attributeName = column.type.substringAfterLast('.')
                    val entity =
                        entities.singleOrNull {
                            entityName in
                                setOf(it.qname.name, it.qname.dotted(), "${it.qname.`package`}.${it.qname.name}")
                        } ?: unsupported()
                    val attribute =
                        entity.attributes.singleOrNull { it.qname.name.substringAfterLast('.') == attributeName }
                            ?: unsupported()
                    if (attribute.aggregated) unsupported()
                    Triple(column.name, entity, attribute)
                }
            val entity = targets.map { it.second }.distinct().singleOrNull() ?: unsupported()
            // ApplyRow's canonical entity name has no package arm. Refuse two packaged targets
            // that would become the same write identity even when a column names one package.
            if (entities.count { it.qname.dotted() == entity.qname.dotted() } != 1) unsupported()
            val mapped = targets.map { it.third }
            if (mapped.distinct().size != mapped.size) unsupported()
            if (entity.attributes.none { it.isKey } ||
                entity.attributes.any { (it.isKey || !it.nullable) && it !in mapped }
            ) {
                unsupported()
            }
            val required =
                action.columns
                    .filterNot { it.optional }
                    .map { it.name }
                    .toSet() +
                    targets.filter { it.third.isKey || !it.third.nullable }.map { it.first }
            return PreviewWriter(entity, targets.associate { it.first to it.third }, required)
        }

        private fun unsupported(): Nothing =
            throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "unsupported native writer mapping or value")
    }
}
