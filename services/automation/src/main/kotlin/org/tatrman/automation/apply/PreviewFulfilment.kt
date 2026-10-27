// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.api.scalar
import org.tatrman.automation.runner.Table
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttrp.resolve.TargetResolution
import java.math.BigDecimal

/** Resolves a business key to a journalled obligation; never manufactures an obligation ID. */
internal class PreviewFulfilment private constructor(
    private val target: PreviewWriter.KeyMapping,
    private val action: AutomationAction,
    private val state: TargetResolution.Resolved,
) {
    val entity: String = target.entity.qname.dotted()
    val expectedTypes =
        target.expectedTypes + mapOf("kind" to "text") +
            listOfNotNull(action.transition?.toColumn?.let { it to "text" }).toMap()

    fun validateArguments(names: Set<String>) {
        if (names != expectedTypes.keys) unsupported()
    }

    fun transition(
        arguments: Map<String, JsonElement>,
        snapshot: Snapshot,
        seq: Int,
    ): ApplyRow? {
        validateArguments(arguments.keys)
        val key = target.key(arguments.filterKeys { it in target.expectedTypes })
        val kind = text(arguments["kind"])
        val transition = requireNotNull(action.transition)
        val to = transition.to ?: text(arguments[transition.toColumn])
        if (to !in STATES || state.attribute.states.isNotEmpty() && to !in state.attribute.states) unsupported()
        val found = snapshot.find(entity, key, kind)
        val active = found.filter { it.state in ACTIVE }
        if (active.size > 1) unsupported()
        val current =
            active.singleOrNull() ?: run {
                // Closed historical rows do not authorize choosing an arbitrary ID. A repeated already-complete
                // operation is silent only if every matching historical row has the requested state.
                if (found.isNotEmpty() && found.all { it.state == to }) return null
                unsupported()
            }
        if (current.state == to) return null
        if (transition.from.isNotEmpty() && current.state !in transition.from) unsupported()
        if (state.attribute.transitions.isNotEmpty() && to !in state.attribute.transitions[current.state].orEmpty()) {
            unsupported()
        }
        return ApplyRow(
            seq,
            state.entity.qname.dotted(),
            "transition",
            buildJsonObject { put("id", current.id) },
            attribute = "state",
            from = listOf(current.state),
            to = scalar(to),
            history = true,
        )
    }

    class Snapshot(
        table: Table,
    ) {
        data class Obligation(
            val id: String,
            val entity: String,
            val key: JsonObject,
            val kind: String,
            val state: String,
        )

        private data class Identity(
            val entity: String,
            val key: Map<String, Any>,
            val kind: String,
        )

        private val indexed: Map<Identity, List<Obligation>>

        init {
            if (table.columns.map { it.name } != listOf("id", "entity", "key", "kind", "state") ||
                table.columns.any { it.type != "text" }
            ) {
                unsupported()
            }
            val ids = mutableSetOf<String>()
            indexed =
                table.rows
                    .map { row ->
                        if (row.size != 5) unsupported()
                        val values = row.map { (it as? String)?.takeIf(String::isNotBlank) ?: unsupported() }
                        if (!ids.add(values[0]) || values[4] !in STATES) unsupported()
                        val key =
                            try {
                                Json.decodeFromString(KeyDecoder, values[2])
                            } catch (
                                _: Exception,
                            ) {
                                unsupported()
                            }
                        canonical(key)
                        Obligation(values[0], values[1], key, values[3], values[4])
                    }.groupBy { Identity(it.entity, canonical(it.key), it.kind) }
        }

        fun find(
            entity: String,
            key: JsonObject,
            kind: String,
        ): List<Obligation> = indexed[Identity(entity, canonical(key), kind)].orEmpty()
    }

    /** Equal effects share one row; opposing transitions of an existing ID cannot form a preview. */
    class Effects {
        private val rowsByKey = mutableMapOf<JsonObject, ApplyRow>()

        fun add(
            row: ApplyRow?,
            rows: MutableList<ApplyRow>,
        ): List<Int> {
            if (row == null) return emptyList()
            val key = requireNotNull(row.key)
            val previous = rowsByKey[key]
            if (previous != null) {
                if (previous.copy(seq = row.seq) != row) unsupported()
                return listOf(previous.seq)
            }
            rowsByKey[key] = row
            rows += row
            return listOf(row.seq)
        }
    }

    companion object {
        private val ACTIVE = setOf("open", "overdue", "reopened")
        private val STATES = ACTIVE + setOf("fulfilled", "cancelled")

        fun resolve(
            action: AutomationAction,
            metadata: PreviewMetadata,
            pinned: Map<String, TargetResolution.Resolved>?,
        ): PreviewFulfilment {
            val transition = action.transition ?: unsupported()
            if ((transition.to == null) == (transition.toColumn == null) ||
                action.set != null ||
                action.columns
                    .map { it.name }
                    .distinct()
                    .size != action.columns.size ||
                action.columns.singleOrNull { it.name == "kind" }?.type != "text" ||
                transition.toColumn?.let { name -> action.columns.singleOrNull { it.name == name }?.type != "text" } ==
                true ||
                transition.to?.let { it !in STATES } == true
            ) {
                unsupported()
            }
            val entity = metadata.entities.singleOrNull { it.qname.dotted() == "er.entity.obligation" } ?: unsupported()
            if (!entity.sourceFile.startsWith("/automation-stdlib/") ||
                listOf("id", "entity", "key", "kind", "state").any { name ->
                    entity.attributes.singleOrNull { it.qname.name.substringAfterLast('.') == name }?.type != "text"
                }
            ) {
                unsupported()
            }
            val attribute =
                entity.attributes.singleOrNull { it.qname.name.substringAfterLast('.') == "state" && it.type == "text" }
                    ?: unsupported()
            val key = entity.attributes.filter { it.isKey }
            if (key.size != 1 ||
                key
                    .single()
                    .qname.name
                    .substringAfterLast('.') != "id" ||
                key.single().type != "text"
            ) {
                unsupported()
            }
            val resolved =
                if (pinned != null) {
                    pinned[transition.attribute] ?: unsupported()
                } else {
                    if (transition.attribute != "obligation.state") unsupported()
                    TargetResolution.Resolved(entity, attribute)
                }
            if (resolved.entity != entity || resolved.attribute != attribute) unsupported()
            val target =
                PreviewWriter.resolveKey(
                    action.columns.filter {
                        it.name !in setOf("kind", transition.toColumn)
                    },
                    metadata.entities,
                    pinned,
                )
            return PreviewFulfilment(target, action, resolved)
        }

        /** Decode as a stream so duplicate object members cannot silently choose a different business key. */
        private object KeyDecoder : DeserializationStrategy<JsonObject> {
            override val descriptor = MapSerializer(String.serializer(), JsonElement.serializer()).descriptor

            override fun deserialize(decoder: Decoder): JsonObject {
                val input = decoder.beginStructure(descriptor)
                val values = linkedMapOf<String, JsonElement>()
                while (true) {
                    val index = input.decodeElementIndex(descriptor)
                    if (index == CompositeDecoder.DECODE_DONE) break
                    if (index % 2 != 0) unsupported()
                    val name = input.decodeStringElement(descriptor, index)
                    val valueIndex = input.decodeElementIndex(descriptor)
                    if (valueIndex != index + 1 || name in values) unsupported()
                    values[name] = input.decodeSerializableElement(descriptor, valueIndex, JsonElement.serializer())
                }
                input.endStructure(descriptor)
                return JsonObject(values)
            }
        }

        private fun canonical(key: JsonObject): Map<String, Any> {
            if (key.isEmpty()) unsupported()
            return key.mapValues { (_, value) ->
                val primitive = value as? JsonPrimitive ?: unsupported()
                when {
                    primitive == JsonNull -> unsupported()
                    primitive.isString -> primitive.content
                    primitive.booleanOrNull != null -> primitive.boolean
                    else ->
                        try {
                            BigDecimal(primitive.content).stripTrailingZeros()
                        } catch (
                            _: Exception,
                        ) {
                            unsupported()
                        }
                }
            }
        }

        private fun text(value: JsonElement?): String =
            (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: unsupported()

        private fun unsupported(): Nothing =
            throw AutomationException(
                ErrorCode.PREVIEW_UNSUPPORTED,
                "unsupported obligation transition mapping or snapshot",
            )
    }
}
