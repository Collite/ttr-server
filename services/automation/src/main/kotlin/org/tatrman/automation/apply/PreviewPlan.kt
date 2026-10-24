// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.tatrman.automation.apply

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * AU contracts §4's multi-entity apply subset. This is a preview, never an executable DML plan.
 * EN RowBatch targets one entity/table and calls its values `values`; AU carries ordered entity rows,
 * explicit set/transition fields and a run. P2 must resolve this subset through EntryApplyResolver and
 * apply lowering before any write, including writable/state/history checks. No Platform entry API is used.
 */
@Serializable
data class ApplyRow(
    val seq: Int,
    val entity: String,
    val op: String,
    val key: JsonObject? = null,
    val row: JsonObject? = null,
    val set: JsonObject? = null,
    val attribute: String? = null,
    val from: List<String> = emptyList(),
    val to: JsonElement? = null,
    val history: Boolean? = null,
    val group: JsonObject? = null,
) {
    init {
        require(seq > 0 && entity.isNotBlank())
        require(op in setOf("insert", "update", "transition"))
        require((op == "insert") == (row != null))
        require((op == "update") == (set != null))
        require((op == "transition") == (attribute != null && to != null && to != JsonNull))
        require(op == "insert" || !key.isNullOrEmpty())
        require(key == null || key.values.none { it == JsonNull || it !is JsonPrimitive })
        require(op == "transition" || (attribute == null && from.isEmpty() && to == null && history == null))
    }

    /** batch_rows.row keeps inserts directly, and the named set/transition payload for other operations. */
    fun storedRow(): JsonObject =
        if (op == "insert") {
            requireNotNull(row)
        } else {
            buildJsonObject {
                set?.let { put("set", it) }
                attribute?.let { put("attribute", it) }
                if (from.isNotEmpty()) put("from", JsonArray(from.map(::JsonPrimitive)))
                to?.let { put("to", it) }
                history?.let { put("history", it) }
            }
        }

    companion object {
        fun restore(
            seq: Int,
            entity: String,
            op: String,
            key: JsonElement?,
            data: JsonElement?,
            group: JsonElement?,
        ): ApplyRow {
            val fields = if (op == "insert") mapOf("row" to requireNotNull(data)) else requireNotNull(data).jsonObject
            return Json.decodeFromJsonElement(
                JsonObject(
                    fields +
                        buildJsonObject {
                            put("seq", seq)
                            put("entity", entity)
                            put("op", op)
                            key?.let { put("key", it) }
                            group?.let { put("group", it) }
                        },
                ),
            )
        }
    }
}

/** One route row × wiring. Connector actions remain evidence; no fictitious model DML is synthesized for them. */
@Serializable
data class DryRunAction(
    val key: JsonObject,
    val route: String,
    val action: String,
    val sink: String,
    val arguments: JsonObject,
    val batchRows: List<Int>,
    val would: String? = null,
    @EncodeDefault val event: String = "preview",
    @EncodeDefault val dryRun: Boolean = true,
) {
    init {
        require(event == "preview" && dryRun)
    }

    /** Build without re-encoding nested JSON numeric literals through floating point. */
    fun payload(): JsonObject =
        buildJsonObject {
            put("key", key)
            put("route", route)
            put("action", action)
            put("sink", sink)
            put("arguments", arguments)
            put("batchRows", JsonArray(batchRows.map(::JsonPrimitive)))
            would?.let { put("would", it) }
            put("event", event)
            put("dryRun", dryRun)
        }
}

@Serializable
data class PreviewPlan(
    val batch: String,
    val run: String,
    val rows: List<ApplyRow>,
    val actions: List<DryRunAction>,
    @EncodeDefault val mode: String = "preview",
) {
    init {
        require(mode == "preview")
        require(rows.map { it.seq } == (1..rows.size).toList())
        require(actions.all { a -> a.batchRows.all { it in 1..rows.size } })
    }

    fun evidence(): PreviewWrites = PreviewWrites(batch, rows.size, rows.groupingBy { it.entity }.eachCount())
}

@Serializable
data class PreviewWrites(
    val batch: String,
    val rows: Int,
    val entities: Map<String, Int>,
    @EncodeDefault val state: String = "preview",
)

/** Public apply envelope; action descriptions are read through run status and protocol. */
@Serializable
data class RunBatchPlan(
    val batch: String,
    val run: String,
    val rows: List<ApplyRow>,
    val mode: String,
)
