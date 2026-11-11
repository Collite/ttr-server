// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.scalar
import org.tatrman.automation.apply.ApplyPlanner
import org.tatrman.automation.apply.ApplyRow
import org.tatrman.automation.apply.DryRunAction
import org.tatrman.automation.apply.ObligationKeys
import org.tatrman.automation.apply.PlanSource
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.runner.Door
import org.tatrman.automation.runner.DoorWrite
import org.tatrman.automation.runner.DoorWriteFailure
import org.tatrman.plan.v1.RowWrites as RowWritesProto
import org.tatrman.ttr.metadata.model.Entity
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/** One `batch_rows` outcome. [prior] is the state read before an `update`/`transition` (the history `from`, `-rev`). */
data class RowOutcome(
    val seq: Int,
    val status: String,
    val error: String? = null,
    val prior: JsonObject? = null,
) {
    init {
        require(status in setOf("staged", "applied", "failed", "skipped"))
    }

    companion object {
        /** A request that may have committed: never resent as if it had failed (review-235). Its rows stay `staged`. */
        const val UNKNOWN = "unknown-outcome"

        /** The durable marker set before a request leaves; found again on a retried close, it means [UNKNOWN]. */
        const val IN_FLIGHT = "in-flight"
    }
}

/** Durable per-group progress, so a retried close never resends a request whose outcome it cannot know. */
interface ApplyJournal {
    fun begin(seqs: List<Int>)

    fun record(outcomes: List<RowOutcome>)
}

/** What a committed run applies: the journaled plan and the context pinned at Open. */
data class ApplyInput(
    val run: UUID,
    val world: String,
    val identity: RunIdentity,
    val modelFp: String,
    val timeout: Duration,
    val rows: List<ApplyRow>,
    val actions: List<DryRunAction>,
    val source: PlanSource,
    val entities: List<Entity>,
    val routes: List<String>,
    /** Outcomes a previous close attempt recorded; their rows are never sent again. */
    val recorded: Map<Int, RowOutcome> = emptyMap(),
)

/**
 * Contracts §4 at the close of an `auto` run (D-163): one [Door.write] per (storage, entity group), in `seq` order,
 * each in one door transaction. Checked here before any door call: the world's `writable:` per storage (⚑AU-P2-4,
 * `not-writable` for the group) and the declared transitions (`bad-transition`). A compare-and-set that answers 0 is
 * `stale-state` and the verdict stands (D-67); a silent self-transition (`prior` = `to`) writes no history (D-85).
 *
 * A transition's `state_history` row goes as the next request, to the history storage (`automation_db`). **The
 * pair is not atomic**: the ERP and `automation_db` are two databases (D-118/D-136), so the transition can commit
 * while its history request fails; the transition row then stays `applied` and its error names the history outcome.
 *
 * Divergence from `ttrp-frontend/entry` (`RowBatch`, `EntryApplyResolver`): the §4 subset is multi-entity and ordered,
 * and the Platform's entry substrate is not a dependency (§4).
 */
class Apply(
    private val door: Door,
    private val clock: Clock,
) {
    suspend fun apply(
        input: ApplyInput,
        journal: ApplyJournal,
    ): Map<Int, RowOutcome> {
        val outcomes = input.recorded.toMutableMap()
        // A request that left before a crash has an unknown outcome: record it, never resend it.
        val lost =
            input.recorded.values
                .filter { it.status == "staged" && it.error == RowOutcome.IN_FLIGHT }
                .map { it.copy(error = RowOutcome.UNKNOWN) }
        if (lost.isNotEmpty()) {
            journal.record(lost)
            lost.forEach { outcomes[it.seq] = it }
        }
        val partners = partners(input.rows, input.actions)
        for (group in ApplyPlanner.groups(input.rows, input.source)) {
            val pending =
                group.rows.filter {
                    outcomes[it.seq]?.status in setOf(null, "staged") &&
                        outcomes[it.seq]?.error == null
                }
            if (pending.isEmpty()) continue
            val result = applyGroup(input, group.storage, pending, partners, journal)
            outcomes += result
        }
        return outcomes
    }

    private suspend fun applyGroup(
        input: ApplyInput,
        storage: String,
        rows: List<ApplyRow>,
        partners: Map<Int, Int>,
        journal: ApplyJournal,
    ): Map<Int, RowOutcome> {
        val out = linkedMapOf<Int, RowOutcome>()
        val lowering = RowWrites(input.entities, input.routes)

        fun done(list: List<RowOutcome>) {
            list.forEach { out[it.seq] = it }
            journal.record(list)
        }
        val refused =
            try {
                lowering.unwritable(rows, input.source.writable[storage].orEmpty())
            } catch (e: RowRefusal) {
                // A target the pinned metadata does not know: no door call, and the true code, not `not-writable`.
                done(rows.map { RowOutcome(it.seq, "failed", e.code) })
                return out
            }
        if (refused.isNotEmpty()) {
            val bad = refused.map { it.seq }.toSet()
            done(rows.map { RowOutcome(it.seq, if (it.seq in bad) "failed" else "skipped", "not-writable") })
            return out
        }
        val units = mutableListOf<WriteUnit>()
        val failed = mutableListOf<RowOutcome>()
        val consumed = mutableSetOf<Int>()
        for (row in rows) {
            if (row.seq in consumed) continue
            val members =
                if (row.op == "update" || row.op == "transition") {
                    rows
                        .filter {
                            it.seq == row.seq ||
                                partners[it.seq] == partners[row.seq] &&
                                partners[row.seq] != null
                        }.filter { it.op == "update" || it.op == "transition" }
                        .filter { it.entity == row.entity && it.key == row.key }
                } else {
                    listOf(row)
                }
            consumed += members.map { it.seq }
            try {
                when (row.op) {
                    "insert" -> units += lowering.insert(row, ifAbsent = row.entity == OBLIGATION)
                    "delete" -> units += lowering.delete(row)
                    else -> {
                        val transitions = members.filter { it.op == "transition" }
                        if (transitions.size >
                            1
                        ) {
                            throw RowRefusal("bad-transition", "two transitions on one key in one action")
                        }
                        units += lowering.update(members.filter { it.op == "update" }, transitions.singleOrNull())
                    }
                }
            } catch (e: RowRefusal) {
                failed += members.map { RowOutcome(it.seq, "failed", e.code) }
            }
        }
        if (failed.isNotEmpty()) done(failed)
        if (units.isEmpty()) return out
        val seqs = units.flatMap { it.seqs }.distinct()
        journal.begin(seqs)
        val answer =
            try {
                door.write(
                    DoorWrite(
                        input.run,
                        "apply/$storage/${seqs.first()}",
                        input.world,
                        storage,
                        RowWritesProto.newBuilder().addAllRows(units.map { it.row }).build(),
                        lowering.parameters,
                        input.identity,
                        input.modelFp,
                        input.timeout,
                    ),
                )
            } catch (e: DoorWriteFailure.Refused) {
                done(seqs.map { RowOutcome(it, "failed", e.code) })
                return out
            } catch (e: DoorWriteFailure.RowFailed) {
                val bad =
                    units
                        .getOrNull(e.seq)
                        ?.seqs
                        .orEmpty()
                        .toSet()
                done(
                    seqs.map {
                        if (it in
                            bad
                        ) {
                            RowOutcome(it, "failed", e.code)
                        } else {
                            RowOutcome(it, "skipped", "rolled-back")
                        }
                    },
                )
                return out
            } catch (e: DoorWriteFailure.Unknown) {
                done(seqs.map { RowOutcome(it, "staged", RowOutcome.UNKNOWN) })
                return out
            }
        val results = linkedMapOf<Int, RowOutcome>()
        val histories = mutableListOf<Pair<ApplyRow, JsonObject>>()
        for ((index, unit) in units.withIndex()) {
            val affected = answer.rowsAffected[index]
            val prior = answer.prior[index]
            when (unit.kind) {
                WriteUnit.Kind.INSERT ->
                    results[unit.seqs.single()] =
                        if (affected ==
                            1L
                        ) {
                            RowOutcome(unit.seqs.single(), "applied")
                        } else {
                            RowOutcome(unit.seqs.single(), "failed", "rows-affected-$affected")
                        }
                WriteUnit.Kind.IF_ABSENT ->
                    results[unit.seqs.single()] =
                        if (affected ==
                            1L
                        ) {
                            RowOutcome(unit.seqs.single(), "applied")
                        } else {
                            RowOutcome(unit.seqs.single(), "skipped", "if_absent")
                        }
                // A key-scoped delete answers 0 for a route the key never had; each per-route statement is one row.
                WriteUnit.Kind.DELETE -> results[unit.seqs.single()] = RowOutcome(unit.seqs.single(), "applied")
                WriteUnit.Kind.UPDATE ->
                    unit.seqs.forEach {
                        results[it] =
                            if (affected >
                                0
                            ) {
                                RowOutcome(it, "applied", prior = prior)
                            } else {
                                RowOutcome(it, "failed", "no-row", prior)
                            }
                    }
                WriteUnit.Kind.CAS -> {
                    val transition = requireNotNull(unit.transition)
                    if (affected == 0L) {
                        unit.seqs.forEach { results[it] = RowOutcome(it, "failed", "stale-state", prior) }
                    } else {
                        unit.seqs.forEach { results[it] = RowOutcome(it, "applied", prior = prior) }
                        val from = prior?.get(requireNotNull(transition.attribute))
                        if (transition.history == true &&
                            from != transition.to
                        ) {
                            histories += transition to (prior ?: JsonObject(emptyMap()))
                        }
                    }
                }
            }
        }
        done(results.values.toList())
        if (histories.isNotEmpty()) {
            history(input, histories)?.let { error ->
                done(histories.map { (t, _) -> out.getValue(t.seq).copy(error = error) })
            }
        }
        return out
    }

    /** The next request, to the history storage: one `state_history` row per transition that moved. Null = written. */
    private suspend fun history(
        input: ApplyInput,
        histories: List<Pair<ApplyRow, JsonObject>>,
    ): String? {
        val storage = input.source.historyStorage ?: return "history-failed: storage-unmapped"
        val lowering = RowWrites(input.entities, input.routes)
        val at = clock.instant().atOffset(ZoneOffset.UTC).toLocalDateTime()
        val rows =
            try {
                val values =
                    histories.map { (transition, prior) ->
                        val key = requireNotNull(transition.key)
                        val text = ObligationKeys.canonicalText(key)
                        val identity =
                            JsonArray(
                                listOf(
                                    JsonPrimitive("automation/state-history/v1"),
                                    scalar(input.run.toString()),
                                    scalar(transition.entity),
                                    JsonPrimitive(text),
                                    scalar(requireNotNull(transition.attribute)),
                                    JsonPrimitive(transition.seq),
                                ),
                            )
                        buildJsonObject {
                            put(
                                "id",
                                UUID.nameUUIDFromBytes(identity.toString().toByteArray(Charsets.UTF_8)).toString(),
                            )
                            put("entity", transition.entity)
                            put("key", text)
                            put("attribute", transition.attribute)
                            put("from_state", prior[requireNotNull(transition.attribute)] ?: JsonNull)
                            put("to_state", requireNotNull(transition.to))
                            put("run", input.run.toString())
                            put("at", at.toString())
                            put("by", input.identity.principal)
                        }
                    }
                if (lowering
                        .unwritable(
                            values.map {
                                ApplyRow(1, HISTORY, "insert", row = it)
                            },
                            input.source.writable[storage].orEmpty(),
                        ).isNotEmpty()
                ) {
                    return "history-failed: not-writable"
                }
                values.map { lowering.history(it) }
            } catch (e: RowRefusal) {
                return "history-failed: ${e.code}"
            }
        return try {
            door.write(
                DoorWrite(
                    input.run,
                    "history/$storage/${histories.first().first.seq}",
                    input.world,
                    storage,
                    RowWritesProto.newBuilder().addAllRows(rows).build(),
                    lowering.parameters,
                    input.identity,
                    input.modelFp,
                    input.timeout,
                ),
            )
            null
        } catch (e: DoorWriteFailure.Unknown) {
            "history-${RowOutcome.UNKNOWN}: ${e.code}"
        } catch (e: DoorWriteFailure) {
            "history-failed: ${e.code}"
        }
    }

    /** seq → the index of the action whose `batchRows` hold it: an action's `set:` and `transition` are one CAS. */
    private fun partners(
        rows: List<ApplyRow>,
        actions: List<DryRunAction>,
    ): Map<Int, Int> {
        val bySeq = rows.associateBy { it.seq }
        return buildMap {
            actions.forEachIndexed { index, action ->
                val members =
                    action.batchRows.mapNotNull { bySeq[it] }.filter {
                        it.op == "update" ||
                            it.op == "transition"
                    }
                if (members.size > 1) members.forEach { put(it.seq, index) }
            }
        }
    }

    companion object {
        const val OBLIGATION = "er.entity.obligation"
        const val HISTORY = "er.entity.state_history"
    }
}
