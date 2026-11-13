// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    /** review-258 B3: the door request the row was sent in (read back from the journal; never written from here). */
    val request: String? = null,
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

        /** review-258 B3: an unknown outcome a re-read found applied ([Apply.reconcile]). */
        const val RECONCILED = "reconciled"

        /** The `state_history` sub-outcomes: `pending` → `applied` | `failed` | `unknown` (a lost commit; not resent). */
        val HISTORY_STATES = setOf(HISTORY_PENDING, "applied", "failed", "unknown")
    }
}

/** Durable per-group progress, so a retried close never resends a request whose outcome it cannot know. */
interface ApplyJournal {
    /** The in-flight mark, set before [request] (a fresh id per door request) leaves with [seqs]. */
    fun begin(
        seqs: List<Int>,
        request: String,
    )

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
    /**
     * review-258 B1: seqs that are one write beside [actions]' (an action's `set:` and `transition` on one key are one
     * compare-and-set). A revert batch has no actions; its inverse units keep the original pairing ([Revert.inverse]).
     */
    val units: List<List<Int>> = emptyList(),
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
        val partners = partners(input.rows, input.actions, input.units)
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
            val members = members(row, rows, partners)
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
        journal.begin(seqs, UUID.randomUUID().toString())
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

    /**
     * seq → the index of the action (or the [units] entry) whose rows hold it: an action's `set:` and `transition` are
     * one CAS.
     */
    private fun partners(
        rows: List<ApplyRow>,
        actions: List<DryRunAction>,
        units: List<List<Int>> = emptyList(),
    ): Map<Int, Int> {
        val bySeq = rows.associateBy { it.seq }
        return buildMap {
            (actions.map { it.batchRows } + units).forEachIndexed { index, seqs ->
                val members =
                    seqs.mapNotNull { bySeq[it] }.filter {
                        it.op == "update" ||
                            it.op == "transition"
                    }
                if (members.size > 1) members.forEach { put(it.seq, index) }
            }
        }
    }

    /** The rows of [rows] that went in one write with [row] (see [partners]). */
    private fun members(
        row: ApplyRow,
        rows: List<ApplyRow>,
        partners: Map<Int, Int>,
    ): List<ApplyRow> =
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

    /**
     * review-258 B3, round three (D-188; Amendment 2026-11-11: an unknown outcome is re-read before any retry or
     * revert). Rows recorded `unknown-outcome` (or a crash's `in-flight` mark) are settled **per lost door request**:
     * the rows sent in one request ([ApplyRow.REQUEST]) committed together or not at all. Each key the request wrote is
     * re-read through [RowWrites.probe] (nothing is written) and compared with the request's **last** effect on it, its
     * units composed in send order:
     * - every key shows that last effect → the whole request applied (`applied`, error [RowOutcome.RECONCILED]). Each
     *   unit's `prior` is what the request's previous unit on that key wrote; the first unit's, only what the row
     *   itself determines (a transition's single `from`). A unit whose prior stays unknown records none, and a revert
     *   refuses it. When [owesHistory], a transition with a known `from` owes its history (stamped with the re-read's
     *   time);
     * - otherwise nothing is proven — a later write, in the same request or by another writer, can hide an applied
     *   one — and the rows stay unknown. Only with [resend] (a held commit's or a revert's own retry), a unit that is
     *   the request's only write to its key, carries a transition, and whose key is now in a state the transition's
     *   compare-and-set accepts (and not its `to`), is made sendable again: its CAS guards the resend.
     *
     * A request with a delete, a key that cannot be read back, a row without a recorded request, or a re-read the door
     * refuses or loses, stays unknown. Answers the outcomes it recorded.
     */
    suspend fun reconcile(
        input: ApplyInput,
        journal: ApplyJournal,
        owesHistory: Boolean = true,
        resend: Boolean = false,
    ): Map<Int, RowOutcome> {
        val bySeq = input.rows.associateBy { it.seq }
        val requests =
            input.recorded.values
                .filter {
                    it.status == "staged" && (it.error == RowOutcome.UNKNOWN || it.error == RowOutcome.IN_FLIGHT)
                }.filter { it.request != null && it.seq in bySeq }
                .groupBy { requireNotNull(it.request) }
        if (requests.isEmpty()) return emptyMap()
        val partners = partners(input.rows, input.actions, input.units)
        val out = linkedMapOf<Int, RowOutcome>()
        for (lost in requests.values) {
            val rows = lost.map { bySeq.getValue(it.seq) }.sortedBy { it.seq }
            val settled = settle(input, rows, partners, owesHistory, resend)
            if (settled.isEmpty()) continue
            journal.record(settled)
            settled.forEach { out[it.seq] = it }
        }
        return out
    }

    /** A key a lost request wrote: the entity and its key object. */
    private data class Target(
        val entity: String,
        val key: JsonObject,
    )

    /** One lost request's rows → their settled outcomes (empty: they stay unknown). See [reconcile]. */
    private suspend fun settle(
        input: ApplyInput,
        rows: List<ApplyRow>,
        partners: Map<Int, Int>,
        owesHistory: Boolean,
        resend: Boolean,
    ): List<RowOutcome> {
        val group = ApplyPlanner.groups(rows, input.source).singleOrNull() ?: return emptyList()
        val lowering = RowWrites(input.entities, input.routes, input.inverse)
        // The request's write units, in the order it sent them.
        val units = mutableListOf<List<ApplyRow>>()
        val consumed = mutableSetOf<Int>()
        for (row in rows) {
            if (row.seq in consumed) continue
            val members = members(row, rows, partners)
            consumed += members.map { it.seq }
            units += members
        }
        if (units.any { unit -> unit.any { it.op != "insert" && it.op != "update" && it.op != "transition" } }) {
            return emptyList()
        }
        val byTarget = linkedMapOf<Target, MutableList<List<ApplyRow>>>()
        try {
            for (unit in units) {
                val anchor = unit.first()
                val key =
                    if (anchor.op == "insert") {
                        val values = requireNotNull(anchor.row)
                        val names = lowering.keyNames(anchor.entity)
                        if (names.isEmpty() || names.any { values[it] == null || values[it] == JsonNull }) {
                            return emptyList()
                        }
                        JsonObject(names.associateWith { values.getValue(it) })
                    } else {
                        requireNotNull(anchor.key)
                    }
                byTarget.getOrPut(Target(anchor.entity, key)) { mutableListOf() } += unit
            }
        } catch (_: RowRefusal) {
            return emptyList()
        }
        // A key both inserted and updated by one request is not told apart here.
        if (byTarget.values.any { us ->
                us
                    .flatten()
                    .map { it.op == "insert" }
                    .distinct()
                    .size > 1
            }
        ) {
            return emptyList()
        }
        val targets = byTarget.keys.toList()
        val probes =
            try {
                targets.map { t ->
                    val us = byTarget.getValue(t).flatten()
                    val attributes =
                        if (us.first().op == "insert") {
                            t.key.keys.toList()
                        } else {
                            us
                                .flatMap { written(it).keys }
                                .distinct()
                        }
                    lowering.probe(t.entity, t.key, attributes)
                }
            } catch (_: RowRefusal) {
                return emptyList()
            }
        val answer =
            try {
                door.write(
                    DoorWrite(
                        input.run,
                        "reconcile/${group.storage}/${rows.first().seq}",
                        input.world,
                        group.storage,
                        RowWritesProto.newBuilder().addAllRows(probes).build(),
                        lowering.parameters,
                        input.identity,
                        input.modelFp,
                        remaining(input),
                    ),
                )
            } catch (_: DoorWriteFailure) {
                return emptyList()
            }
        val current = targets.withIndex().associate { (i, t) -> t to answer.prior.getOrNull(i) }
        val landed =
            targets.all { t ->
                val now = current[t] ?: return@all false
                val us = byTarget.getValue(t).flatten()
                us.first().op == "insert" ||
                    us.fold(mapOf<String, JsonElement>()) { acc, r -> acc + written(r) }.all { (a, v) ->
                        same(now[a], v)
                    }
            }
        val now = clock.instant()
        if (landed) {
            return byTarget.values.flatMap { us ->
                val known = mutableMapOf<String, JsonElement>()
                us.flatMap { unit ->
                    val transition = unit.firstOrNull { it.op == "transition" }
                    val prior =
                        if (unit.first().op == "insert") {
                            null
                        } else {
                            unit
                                .flatMap { written(it).keys }
                                .distinct()
                                .associateWith { a ->
                                    known[a]
                                        ?: transition
                                            ?.takeIf { it.attribute == a && it.from.size == 1 }
                                            ?.let { JsonPrimitive(it.from.single()) }
                                }.takeIf { m -> m.values.none { it == null } }
                                ?.let { m -> JsonObject(m.mapValues { requireNotNull(it.value) }) }
                        }
                    unit.forEach { known += written(it) }
                    val from = transition?.let { prior?.get(requireNotNull(it.attribute)) }
                    val owed = owesHistory && transition?.history == true && from != null && !same(from, transition.to)
                    unit.map {
                        val history = owed && it.seq == transition?.seq
                        RowOutcome(
                            it.seq,
                            "applied",
                            RowOutcome.RECONCILED,
                            prior,
                            RowOutcome.HISTORY_PENDING.takeIf { history },
                            now.takeIf { history },
                        )
                    }
                }
            }
        }
        if (!resend) return emptyList()
        return byTarget.entries.flatMap { (t, us) ->
            val unit = us.singleOrNull() ?: return@flatMap emptyList()
            val transition = unit.firstOrNull { it.op == "transition" } ?: return@flatMap emptyList()
            val state = (current[t]?.get(requireNotNull(transition.attribute)) as? JsonPrimitive)?.contentOrNull
            val legal = lowering.legalFrom(transition)
            if (state == null || legal == null || state !in legal || same(JsonPrimitive(state), transition.to)) {
                emptyList()
            } else {
                unit.map { RowOutcome(it.seq, "staged") }
            }
        }
    }

    /** What [row] writes, by attribute (a transition its `to`, an update its `set:`; an insert nothing here). */
    private fun written(row: ApplyRow): Map<String, JsonElement> =
        when (row.op) {
            "transition" -> mapOf(requireNotNull(row.attribute) to requireNotNull(row.to))
            "update" -> requireNotNull(row.set)
            else -> emptyMap()
        }

    private fun same(
        a: JsonElement?,
        b: JsonElement?,
    ): Boolean {
        val left = a ?: JsonNull
        val right = b ?: JsonNull
        if (left is JsonNull || right is JsonNull) return left is JsonNull && right is JsonNull
        return (left as? JsonPrimitive)?.content == (right as? JsonPrimitive)?.content
    }

    companion object {
        const val OBLIGATION = "er.entity.obligation"
        const val HISTORY = "er.entity.state_history"
    }
}
