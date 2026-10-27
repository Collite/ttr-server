// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.tatrman.automation.apply

import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.api.scalar
import org.tatrman.automation.runner.RunContext
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttrp.resolve.TargetResolution
import java.math.BigDecimal
import java.time.ZoneOffset
import java.util.UUID

/** Proposed open obligations only. Existing-open lookup, fulfilment and signal delivery belong to P2. */
internal class PreviewObligation private constructor(
    private val target: PreviewWriter.KeyMapping,
    private val obligation: PreviewWriter,
) {
    val expectedTypes: Map<String, String> = target.expectedTypes + mapOf("kind" to "text", "due" to "date")

    fun validateArguments(names: Set<String>) {
        if (names != expectedTypes.keys) unsupported()
    }

    fun insert(
        seq: Int,
        arguments: Map<String, JsonElement>,
        context: RunContext,
    ): ApplyRow {
        validateArguments(arguments.keys)
        val key = target.key(arguments.filterKeys { it in target.expectedTypes })
        // Normalize key ordering and exact numeric spellings before naming an obligation. No Double conversion.
        val canonicalKey =
            JsonObject(
                key.toSortedMap().mapValues { (_, value) ->
                    val primitive = value.jsonPrimitive
                    if (primitive.isString || primitive.booleanOrNull != null) {
                        primitive
                    } else {
                        JsonUnquotedLiteral(BigDecimal(primitive.content).stripTrailingZeros().toPlainString())
                    }
                },
            )
        val kind = arguments["kind"] as? JsonPrimitive ?: unsupported()
        if (!kind.isString || kind.content.isBlank()) unsupported()
        val due = arguments["due"]?.takeUnless { it == JsonNull } ?: unsupported()
        val entity = target.entity.qname.dotted()
        val identity =
            JsonArray(
                listOf(
                    JsonPrimitive("automation/obligation-preview/v1"),
                    scalar(context.id),
                    scalar(entity),
                    canonicalKey,
                    kind,
                ),
            )
        val id = UUID.nameUUIDFromBytes(identity.toString().toByteArray(Charsets.UTF_8)).toString()
        return obligation.insert(
            seq,
            mapOf(
                "id" to scalar(id),
                "recipe" to scalar(context.recipe.recipe.name),
                "run" to scalar(context.id),
                "entity" to scalar(entity),
                "key" to scalar(canonicalKey.toString()),
                "kind" to kind,
                "due" to due,
                "state" to scalar("open"),
                "created_at" to scalar(context.startedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()),
            ),
        )
    }

    /** One row per (entity, exact key, kind) in this preview; a conflicting due date refuses the whole plan. */
    class Proposals {
        private val proposed = mutableMapOf<JsonObject, ApplyRow>()

        fun add(
            row: ApplyRow,
            rows: MutableList<ApplyRow>,
        ): Int {
            val key = requireNotNull(row.key)
            val existing = proposed[key]
            if (existing != null) {
                if (existing.entity != row.entity || existing.row != row.row) unsupported()
                return existing.seq
            }
            proposed[key] = row
            rows += row
            return row.seq
        }
    }

    companion object {
        private val FIELDS =
            linkedMapOf(
                "id" to "text",
                "recipe" to "text",
                "run" to "text",
                "entity" to "text",
                "key" to "text",
                "kind" to "text",
                "due" to "date",
                "state" to "text",
                "created_at" to "timestamp",
            )

        fun resolve(
            action: AutomationAction,
            metadata: PreviewMetadata,
            pinned: Map<String, TargetResolution.Resolved>?,
        ): PreviewObligation {
            // No action-name inference: the sink and declared columns describe an insert. A transition
            // still needs a governed lookup of the existing obligation, so it must never become an insert.
            if (action.set != null ||
                action.transition != null ||
                action.columns
                    .map { it.name }
                    .distinct()
                    .size != action.columns.size ||
                action.columns.singleOrNull { it.name == "kind" }?.type != "text" ||
                action.columns.singleOrNull { it.name == "due" }?.type != "date"
            ) {
                unsupported()
            }
            val target =
                PreviewWriter.resolveKey(
                    action.columns.filter {
                        it.name !in setOf("kind", "due")
                    },
                    metadata.entities,
                    pinned,
                )
            val entity = metadata.entities.singleOrNull { it.qname.dotted() == "er.entity.obligation" } ?: unsupported()
            val columns = FIELDS.keys.map { AutomationColumn(it, "obligation.$it") }
            val targets =
                columns.associate { column ->
                    val name = column.type.substringAfterLast('.')
                    val attribute =
                        entity.attributes.singleOrNull { it.qname.name.substringAfterLast('.') == name }
                            ?: unsupported()
                    column.type to TargetResolution.Resolved(entity, attribute)
                }
            val obligation = PreviewWriter.resolve(action.copy(columns = columns), metadata.entities, targets)
            if (obligation.expectedTypes != FIELDS ||
                entity.attributes.filter { it.isKey }.map { it.qname.name.substringAfterLast('.') } != listOf("id")
            ) {
                unsupported()
            }
            return PreviewObligation(target, obligation)
        }

        private fun unsupported(): Nothing =
            throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "unsupported native obligation mapping or value")
    }
}
