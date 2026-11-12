// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.ApplyRow
import org.tatrman.automation.apply.RunBatchPlan
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.NoRunBatch
import org.tatrman.automation.ledger.NoSuchRun
import org.tatrman.automation.ledger.RevertState
import org.tatrman.ttr.metadata.model.Entity
import java.time.Clock
import java.util.UUID

/** `POST /runs/{id}/revert`'s answer: the revert batch and its rows' outcomes. */
@kotlinx.serialization.Serializable
data class RevertResult(
    val batch: String,
    val revOf: String,
    val rows: Int,
    val applied: Int,
    val failed: Int,
)

/**
 * S2.3 `-rev` (D-66; Amendment 2026-11-03): a committed batch's inverse, as a new batch with `rev_of`, applied through
 * [Apply] — the same door requests, compare-and-set and history as any apply. Built from the `prior` recorded at apply
 * time, for the rows that were `applied`:
 * - `update` → an `update` setting the prior values back;
 * - `transition` → the inverse transition (`from: [to]`, `to:` the prior state, with history). An obligation's transition
 *   reopens it this way (no signal, D-142);
 * - a `writer` action's `insert` → a `delete` by the inserted row's primary key.
 * Verdict, detail and current rows, obligation inserts and the current deletes are never reverted.
 *
 * review-258: an action's `set:` and its `transition` on one key were one compare-and-set, and their inverse is one
 * again, guarded by the recorded `to` (B1: a row that moved on since is not touched at all). The units are undone in
 * reverse effect order, so successive writes to one row unwind to the value before the batch (B2). A row whose outcome
 * is unknown is re-read first ([Apply.reconcile]); one that stays unknown, or was applied with no recorded `prior`,
 * refuses the revert before anything is written (`409 batch-not-committed`, reason `unresolved-outcome`), and a
 * revert row whose own outcome stays unknown leaves the original `committed` until a retry settles it (B3).
 *
 * `role.ops` (the run's world), its own budget, and the deferred context as for a held commit. An interrupted revert
 * resumes its journaled batch; a finished one leaves the original `reverted` (`409 batch-reverted` on a second call).
 */
class Revert(
    private val ledger: Ledger,
    private val apply: Apply,
    private val contexts: DeferredContexts,
    private val held: Held,
    private val clock: Clock,
) {
    private val lock = Mutex()

    suspend fun revert(
        run: UUID,
        by: Caller,
    ): RevertResult =
        withContext(NonCancellable) {
            lock.withLock {
                var state = io { ledger.revertState(run) }
                held.requireOps(by, state.names.deployment)
                val context = contexts.resolve(run, state.names, by)
                val deadline = clock.instant().plus(state.budget)

                fun input(
                    rows: List<ApplyRow>,
                    statuses: List<org.tatrman.automation.apply.BatchRowStatus>,
                    units: List<List<Int>>,
                    inverse: Boolean,
                ) = ApplyInput(
                    run,
                    context.world,
                    context.identity,
                    context.modelFp,
                    deadline,
                    rows,
                    if (inverse) emptyList() else state.actions,
                    context.source,
                    context.entities,
                    context.routes,
                    statuses.mapNotNull { it.recorded() }.associateBy { it.seq },
                    inverse = inverse,
                    revOf = state.batch.takeIf { inverse },
                    units = units,
                )
                if (state.rev == null) {
                    // B3: settle the original's unknown outcomes (of the rows a revert undoes) before deciding what to undo.
                    val undone = writerRows(state)
                    val settled =
                        apply.reconcile(
                            input(
                                state.plan.rows.filter { invertible(it, undone) },
                                state.plan.statuses,
                                emptyList(),
                                inverse = false,
                            ),
                            journal(state.batch),
                            // The original is not re-applied: its history is not written after the fact.
                            owesHistory = false,
                        ) { RowOutcome(it, "skipped", RowOutcome.NOT_APPLIED) }
                    if (settled.isNotEmpty()) state = io { ledger.revertState(run) }
                }
                val inverse = inverse(state, context.entities)
                if (state.rev == null && inverse.unresolved.isNotEmpty()) {
                    throw unresolved(state.batch, inverse.unresolved, "of the reverted batch")
                }
                val rev = state.rev ?: io { ledger.beginRevert(run, state.batch, inverse.rows) }

                fun shape(rows: List<ApplyRow>) = rows.map { listOf(it.seq, it.entity, it.op, it.key) }
                check(shape(rev.rows) == shape(inverse.rows)) {
                    "the journaled revert batch differs from the inverse of its original"
                }
                val id = UUID.fromString(rev.batch)
                val revInput = input(rev.rows, rev.statuses, inverse.units, inverse = true)
                // B3: a resumed revert re-reads its own unknown rows; one that did not land is sent again (still guarded).
                val settled = apply.reconcile(revInput, journal(id)) { RowOutcome(it, "staged") }
                val outcomes = apply.apply(revInput.copy(recorded = revInput.recorded + settled), journal(id))
                val open = rev.rows.map { it.seq }.filter { outcomes[it] == null || outcomes[it]?.status == "staged" }
                if (open.isNotEmpty()) throw unresolved(id, open, "of the revert batch")
                val done: RunBatchPlan = io { ledger.finishRevert(run, state.batch, id, by.principal) }
                RevertResult(
                    done.batch,
                    state.batch.toString(),
                    done.rows.size,
                    done.statuses.count { it.status == "applied" },
                    done.statuses.count { it.status == "failed" },
                )
            }
        }

    private fun journal(batch: UUID) =
        object : ApplyJournal {
            override fun begin(seqs: List<Int>) = ledger.beginApply(batch, seqs)

            override fun release(seqs: List<Int>) = ledger.releaseApply(batch, seqs)

            override fun record(outcomes: List<RowOutcome>) = ledger.recordApply(batch, outcomes)
        }

    private fun unresolved(
        batch: UUID,
        seqs: List<Int>,
        which: String,
    ) = AutomationException(
        ErrorCode.BATCH_NOT_COMMITTED,
        "rows $seqs $which have no known outcome or prior (a lost acknowledgement the door could not settle); " +
            "nothing is certified reverted — revert again to re-read them",
        buildJsonObject {
            put("reason", "unresolved-outcome")
            put("batch", batch.toString())
            put("rows", JsonArray(seqs.map(::JsonPrimitive)))
        },
    )

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (_: NoSuchRun) {
                throw AutomationException(ErrorCode.IDENTITY, "the run is not available to this caller")
            } catch (_: NoRunBatch) {
                throw AutomationException(ErrorCode.BATCH_NOT_COMMITTED, "the run has no batch")
            }
        }

    /** A batch's inverse: its rows (numbered from 1), the seqs that are one write, and the rows it cannot undo. */
    data class Inverse(
        val rows: List<ApplyRow>,
        val units: List<List<Int>>,
        val unresolved: List<Int>,
    )

    companion object {
        /**
         * The inverse of [state]'s applied rows. The original's write units — an action's `update`/`transition` rows on
         * one key, sent as one compare-and-set ([Apply]) — stay units, undone last-first (review-258 B1/B2). A unit with
         * an unknown outcome, or applied with no `prior` for one of its `update`/`transition` rows, is unresolved.
         */
        fun inverse(
            state: RevertState,
            entities: List<Entity>,
        ): Inverse {
            val statuses = state.plan.statuses.associateBy { it.seq }
            val bySeq = state.plan.rows.associateBy { it.seq }
            val writerRows = writerRows(state)
            val unitOf = mutableMapOf<Int, Int>()
            for (action in state.actions) {
                action.batchRows
                    .mapNotNull { bySeq[it] }
                    .filter { it.op == "update" || it.op == "transition" }
                    .groupBy { it.entity to it.key }
                    .values
                    .filter { it.size > 1 }
                    .forEach { members -> members.forEach { unitOf[it.seq] = members.minOf { m -> m.seq } } }
            }
            val units =
                state.plan.rows
                    .groupBy { unitOf[it.seq] ?: it.seq }
                    .values
            val rows = mutableListOf<ApplyRow>()
            val writes = mutableListOf<List<Int>>()
            val unresolved = mutableListOf<Int>()
            for (unit in units.reversed()) {
                val unknown =
                    unit.filter {
                        statuses[it.seq]?.status == "staged" &&
                            statuses[it.seq]?.error.let { e -> e == RowOutcome.UNKNOWN || e == RowOutcome.IN_FLIGHT }
                    }
                val applied = unit.filter { statuses[it.seq]?.status == "applied" }
                if (unknown.any { invertible(it, writerRows) }) {
                    unresolved += unknown.map { it.seq }
                    continue
                }
                val inverted = applied.map { invert(it, statuses[it.seq]?.prior, writerRows, entities) }
                if (inverted.any { it == Unresolved }) {
                    unresolved += applied.map { it.seq }
                    continue
                }
                val emitted = inverted.filterIsInstance<Row>().map { it.row }
                if (emitted.isEmpty()) continue
                val seqs = emitted.indices.map { rows.size + it + 1 }
                rows += emitted.mapIndexed { index, row -> row.copy(seq = seqs[index], group = null) }
                if (seqs.size > 1) writes += seqs
            }
            return Inverse(rows, writes, unresolved.sorted())
        }

        /** The rows a revert undoes: `update`, `transition` and a writer action's `insert`. */
        fun invertible(
            row: ApplyRow,
            writerRows: Set<Int>,
        ): Boolean = row.op == "update" || row.op == "transition" || row.op == "insert" && row.seq in writerRows

        private fun writerRows(state: RevertState): Set<Int> =
            state.actions
                .filter { it.sink == "writer" }
                .flatMap { it.batchRows }
                .toSet()

        private sealed interface Inverted

        private data class Row(
            val row: ApplyRow,
        ) : Inverted

        private data object Skip : Inverted

        private data object Unresolved : Inverted

        private fun invert(
            row: ApplyRow,
            prior: JsonObject?,
            writerRows: Set<Int>,
            entities: List<Entity>,
        ): Inverted =
            when (row.op) {
                "update" -> {
                    val set = requireNotNull(row.set)
                    // read_before covers every `set:` column; an absent prior key was NULL in the row.
                    prior?.let { p -> Row(row.copy(set = JsonObject(set.keys.associateWith { p[it] ?: JsonNull }))) }
                        ?: Unresolved
                }
                "transition" -> {
                    val to = (row.to as JsonPrimitive).content
                    val back = prior?.get(requireNotNull(row.attribute))
                    when {
                        back == null || back == JsonNull -> Unresolved
                        // A silent self-transition: no state to restore, but its CAS still guards the unit's `set:`.
                        back == row.to -> Row(row.copy(from = listOf(to), to = row.to, history = false))
                        else -> Row(row.copy(from = listOf(to), to = back, history = true))
                    }
                }
                "insert" ->
                    if (row.seq in writerRows) Row(ApplyRow(1, row.entity, "delete", key(row, entities))) else Skip
                else -> Skip
            }

        /** A writer insert's primary key, read from its own row (the target entity's key attributes). */
        private fun key(
            row: ApplyRow,
            entities: List<Entity>,
        ): JsonObject {
            val entity =
                entities.singleOrNull { it.qname.dotted() == row.entity || "er.entity.${it.qname.name}" == row.entity }
                    ?: throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "revert: unknown entity ${row.entity}")
            val names = entity.attributes.filter { it.isKey }.map { it.qname.name.substringAfterLast('.') }
            val values = requireNotNull(row.row)
            if (names.isEmpty() || names.any { values[it] == null || values[it] == JsonNull }) {
                throw AutomationException(
                    ErrorCode.PREVIEW_UNSUPPORTED,
                    "revert: ${row.entity} has no key to delete by",
                )
            }
            return JsonObject(names.associateWith { values.getValue(it) })
        }
    }
}
