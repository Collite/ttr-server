// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.tatrman.automation.apply

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * AU contracts §4's multi-entity apply subset. This is a preview, never an executable DML plan.
 * EN RowBatch targets one entity/table and calls its values `values`; AU carries ordered entity rows,
 * explicit set/transition fields and a run. D-105 adds a key-scoped current delete to the journal.
 * S2.1 only plans; S2.2 sends the door's D-163 row_writes wire after its producer release, including
 * writable/state/history checks. No Platform entry API is used.
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
        require(op in setOf("insert", "update", "transition", "delete"))
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
        /** `batch_rows.row` key of an applied transition's history sub-outcome (the payload's `history` is §4's flag). */
        const val HISTORY_OUTCOME = "history_outcome"

        /** S2.3 F-3: the instant the transition committed, journaled with `history: pending`; a resent history stamps it. */
        const val HISTORY_AT = "history_at"

        /** S2.3: who decided a held row's group (commit or reject), and when; apply evidence, not §4. */
        const val DECIDED = "decided"

        /** The apply evidence kept beside a row's §4 payload in `batch_rows.row`. */
        val EVIDENCE = setOf("prior", HISTORY_OUTCOME, HISTORY_AT, DECIDED)

        fun restore(
            seq: Int,
            entity: String,
            op: String,
            key: JsonElement?,
            data: JsonElement?,
            group: JsonElement?,
        ): ApplyRow {
            // S2.2 keeps the answered `prior` and a transition's history sub-outcome beside the payload: apply
            // evidence, not §4.
            val fields =
                if (op == "insert") {
                    mapOf("row" to requireNotNull(data))
                } else {
                    requireNotNull(data).jsonObject - EVIDENCE
                }
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
        // S2.2: a wet run journals the same evidence at evaluate (dryRun false); its rows apply at close.
        require(event == "preview")
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
    @kotlinx.serialization.Transient val source: PlanSource? = null,
    @kotlinx.serialization.Transient val groups: List<ApplyGroup> = emptyList(),
    @kotlinx.serialization.Transient val candidateKeys: List<JsonObject> = emptyList(),
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

/**
 * S2.2: a row's apply status (`staged · applied · failed · skipped`), its error, the `prior` it read and, on an applied
 * transition, its `state_history` sub-outcome (`pending · applied · failed · unknown`, Amendment 2026-11-11).
 */
@Serializable
data class BatchRowStatus(
    val seq: Int,
    val status: String,
    val error: String? = null,
    val prior: JsonObject? = null,
    val history: String? = null,
    /** S2.3 F-3: the transition's commit instant, recorded with `history: pending`. */
    val historyAt: String? = null,
    /** S2.3: `{ decision: commit|reject, by, at }` on a held row once its group is decided. */
    val decided: JsonObject? = null,
)

/** Public apply envelope; action descriptions are read through run status and protocol. */
@Serializable
data class RunBatchPlan(
    val batch: String,
    val run: String,
    val rows: List<ApplyRow>,
    val mode: String,
    val statuses: List<BatchRowStatus> = emptyList(),
)

/** S2.2: the durable state a close reads before it applies an `auto` batch. */
data class ApplyState(
    val batch: java.util.UUID,
    val runAs: String,
    val status: org.tatrman.automation.ledger.RunStatus,
    val dryRun: Boolean,
    val plan: RunBatchPlan,
    val actions: List<DryRunAction>,
    /** Rows an earlier close attempt already marked (in flight or answered). */
    val recorded: Map<Int, org.tatrman.automation.writer.RowOutcome>,
    /** The run's original absolute deadline (`started_at` + budget): the apply and the close never pass it. */
    val deadline: java.time.Instant,
)

/** Pinned planning inputs; never part of the public apply JSON. */
data class PlanSource(
    val verdict: org.tatrman.automation.bundle.ManifestVerdictSchema?,
    val storages: Map<String, String>,
    val ledgerStorage: String,
    val obligationStorage: String? = null,
    /** S2.2: where a transition's `state_history` row goes (unbound placement, D-118); null = no storage takes it. */
    val historyStorage: String? = null,
    /** The world's `writable:` entries per storage name, as resolved at Open (⚑AU-P2-4). */
    val writable: Map<String, List<String>> = emptyMap(),
)

/** Detail/current rows share their verdict entity's transaction, preserving global seq within it. */
data class ApplyGroup(
    val storage: String,
    val entity: String,
    val rows: List<ApplyRow>,
)
