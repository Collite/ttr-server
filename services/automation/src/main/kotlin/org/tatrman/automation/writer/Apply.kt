// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** One `batch_rows` outcome. [prior] is the state read before an `update`/`transition` (the history `from`, `-rev`). */
data class RowOutcome(
    val seq: Int,
    val status: String,
    val error: String? = null,
    val prior: JsonObject? = null,
    /** An applied transition's `state_history` sub-outcome (Amendment 2026-11-11, review-244 B1); null otherwise. */
    val history: String? = null,
    /**
     * S2.3 F-3 (✅R244-F): the instant the transition committed, journaled with `history: pending`. Every `state_history`
     * request for this row — the first or a retry — stamps it as `at`, never the retry's own time.
     */
    val committedAt: Instant? = null,
) {
    init {
        require(status in setOf("staged", "applied", "failed", "skipped"))
        require(history == null || history in HISTORY_STATES)
    }

    companion object {
        /** A request that may have committed: never resent as if it had failed (review-235). Its rows stay `staged`. */
        const val UNKNOWN = "unknown-outcome"

        /** The durable marker set before a request leaves; found again on a retried close, it means [UNKNOWN]. */
        const val IN_FLIGHT = "in-flight"

        /**
         * `history: pending` — journaled in the same write as the transition's `applied`; the `state_history` request
         * has not been answered. A retried close sends that request alone (an `if_absent` insert of the row's
         * deterministic id: a row that landed unacknowledged is found, not inserted again); the ERP transition is
         * never resent, and a committed close refuses while one remains.
         */
        const val HISTORY_PENDING = "pending"

        /** The `state_history` sub-outcomes: `pending` → `applied` | `failed` | `unknown` (a lost commit; not resent). */
        val HISTORY_STATES = setOf(HISTORY_PENDING, "applied", "failed", "unknown")
    }
}

/** Durable per-group progress, so a retried close never resends a request whose outcome it cannot know. */
interface ApplyJournal {
    fun begin(seqs: List<Int>)

    /** The request never left: clear only these rows' in-flight marks, preserving any settled outcomes. */
    fun release(seqs: List<Int>)

    fun record(outcomes: List<RowOutcome>)
}

/** What a committed run applies: the journaled plan and the context pinned at Open. */
data class ApplyInput(
    val run: UUID,
    val world: String,
    val identity: RunIdentity,
    val modelFp: String,
    /** The run's original absolute deadline: each request is bounded by what remains, none leaves after it. */
    val deadline: Instant,
    val rows: List<ApplyRow>,
    val actions: List<DryRunAction>,
    val source: PlanSource,
    val entities: List<Entity>,
    val routes: List<String>,
    /** Outcomes a previous close attempt recorded; their rows are never sent again. */
    val recorded: Map<Int, RowOutcome> = emptyMap(),
    /** S2.3: a revert batch — its inverse transitions are not checked against the declared graph ([RowWrites]). */
    val inverse: Boolean = false,
    /**
     * D-187: the batch a revert batch reverts. Its history rows name it in their identity, so they never collide with
     * (and are never skipped as) the reverted batch's own rows, whose `seq` they reuse. `state_history` has no `rev_of`
     * column in the stdlib (⚑ Found 2026-11-12); the revert's protocol entry carries it.
     */
    val revOf: UUID? = null,
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
 * Both outcomes are durable (Amendment 2026-11-11, review-244 B1): the transition is journaled `applied` with
 * `history: pending` in one write, and the sub-outcome is updated after the history request — `applied`, `failed`
 * (`history-failed: …`) or `unknown` (`history-unknown-outcome: …`). A retried close resends only a pending history
 * request, as an `if_absent` insert of the deterministic id (the re-read of `state_history`); never the transition.
 *
 * The run's original absolute deadline holds through the apply (§5.2, B2): every request's timeout is the time left
 * before [ApplyInput.deadline], and none leaves once it has passed (`close-deadline-exceeded`; the outcomes already
 * recorded stay, the batch stays `preview`).
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
            // A transition an earlier attempt applied whose history is unanswered: send that request alone.
            val owed =
                group.rows.filter {
                    outcomes[it.seq]?.status == "applied" && outcomes[it.seq]?.history == RowOutcome.HISTORY_PENDING
                }
            if (owed.isNotEmpty()) {
                outcomes +=
                    history(
                        input,
                        owed.map { it to (outcomes.getValue(it.seq).prior ?: JsonObject(emptyMap())) },
                        outcomes,
                        journal,
                    )
            }
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
        val lowering = RowWrites(input.entities, input.routes, input.inverse)

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
        remaining(input)
        journal.begin(seqs)
        val timeout =
            try {
                // begin is blocking IO: its transaction may have consumed the rest of the run's budget.
                remaining(input)
            } catch (e: AutomationException) {
                journal.release(seqs)
                throw e
            }
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
                        timeout,
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
                        val from = prior?.get(requireNotNull(transition.attribute))
                        val owed = transition.history == true && from != transition.to
                        if (owed) histories += transition to (prior ?: JsonObject(emptyMap()))
                        val committed = clock.instant()
                        unit.seqs.forEach {
                            val history = if (owed && it == transition.seq) RowOutcome.HISTORY_PENDING else null
                            results[it] =
                                RowOutcome(
                                    it,
                                    "applied",
                                    prior = prior,
                                    history = history,
                                    committedAt = committed.takeIf { history != null },
                                )
                        }
                    }
                }
            }
        }
        done(results.values.toList())
        if (histories.isNotEmpty()) out += history(input, histories, out, journal)
        return out
    }

    /**
     * The next request, to the history storage: one `state_history` row per transition that moved, each an `if_absent`
     * insert of its deterministic id. [applied] holds each transition's `applied` outcome; the answer sets its history
     * sub-outcome and is journaled. Past the deadline nothing is sent and the transitions stay `history: pending`.
     */
    private suspend fun history(
        input: ApplyInput,
        histories: List<Pair<ApplyRow, JsonObject>>,
        applied: Map<Int, RowOutcome>,
        journal: ApplyJournal,
    ): Map<Int, RowOutcome> {
        fun settle(
            history: String,
            error: String? = null,
        ): Map<Int, RowOutcome> {
            val list = histories.map { (t, _) -> applied.getValue(t.seq).copy(error = error, history = history) }
            journal.record(list)
            return list.associateBy { it.seq }
        }

        fun failed(code: String) = settle("failed", "history-failed: $code")
        val storage = input.source.historyStorage ?: return failed("storage-unmapped")
        val lowering = RowWrites(input.entities, input.routes, input.inverse)
        val now = clock.instant()
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
                                ) + listOfNotNull(input.revOf?.let { JsonPrimitive("rev_of:$it") }),
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
                            // F-3: the transition's own commit instant, also on a retry; S2.2 rows without it: now.
                            val at = applied[transition.seq]?.committedAt ?: now
                            put("at", at.atOffset(ZoneOffset.UTC).toLocalDateTime().toString())
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
                    return failed("not-writable")
                }
                values.map { lowering.history(it) }
            } catch (e: RowRefusal) {
                return failed(e.code)
            }
        val timeout = remaining(input)
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
                    timeout,
                ),
            )
            // 1 = written now; 0 = an earlier attempt's row landed unacknowledged (the re-read found it).
            settle("applied")
        } catch (e: DoorWriteFailure.Unknown) {
            settle("unknown", "history-${RowOutcome.UNKNOWN}: ${e.code}")
        } catch (e: DoorWriteFailure) {
            failed(e.code)
        }
    }

    /** The time left before the run's original deadline; none left → `close-deadline-exceeded`, nothing is sent. */
    private fun remaining(input: ApplyInput): Duration {
        val left = Duration.between(clock.instant(), input.deadline)
        if (left.toMillis() <= 0) {
            throw AutomationException(
                ErrorCode.CLOSE_DEADLINE_EXCEEDED,
                "the run's original deadline passed during its apply; no further request is sent",
            )
        }
        return left
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
