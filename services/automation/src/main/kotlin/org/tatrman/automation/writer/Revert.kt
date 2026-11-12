// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *   reopens it this way (no signal, D-142). A prior that was null cannot be a transition target: that row is not reverted;
 * - a `writer` action's `insert` → a `delete` by the inserted row's primary key.
 * Verdict, detail and current rows, obligation inserts and the current deletes are never reverted. `role.ops` (the run's
 * world), its own budget, and the deferred context as for a held commit. An interrupted revert resumes its journaled
 * batch; a finished one leaves the original `reverted` (`409 batch-reverted` on a second call).
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
                val state = io { ledger.revertState(run) }
                held.requireOps(by, state.names.deployment)
                val context = contexts.resolve(run, state.names, by)
                val rev = state.rev ?: io { ledger.beginRevert(run, state.batch, inverse(state, context.entities)) }
                val id = UUID.fromString(rev.batch)
                apply.apply(
                    ApplyInput(
                        run,
                        context.world,
                        context.identity,
                        context.modelFp,
                        clock.instant().plus(state.budget),
                        rev.rows,
                        emptyList(),
                        context.source,
                        context.entities,
                        context.routes,
                        rev.statuses.mapNotNull { it.recorded() }.associateBy { it.seq },
                        inverse = true,
                    ),
                    object : ApplyJournal {
                        override fun begin(seqs: List<Int>) = ledger.beginApply(id, seqs)

                        override fun release(seqs: List<Int>) = ledger.releaseApply(id, seqs)

                        override fun record(outcomes: List<RowOutcome>) = ledger.recordApply(id, outcomes)
                    },
                )
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

    companion object {
        /** The inverse rows of [state]'s applied rows, numbered from 1 in the original order. */
        fun inverse(
            state: RevertState,
            entities: List<Entity>,
        ): List<ApplyRow> {
            val statuses = state.plan.statuses.associateBy { it.seq }
            val writerRows =
                state.actions
                    .filter { it.sink == "writer" }
                    .flatMap { it.batchRows }
                    .toSet()
            val out = mutableListOf<ApplyRow>()
            for (row in state.plan.rows) {
                val status = statuses[row.seq] ?: continue
                if (status.status != "applied") continue
                val prior = status.prior
                val inverse =
                    when (row.op) {
                        "update" -> {
                            val set = requireNotNull(row.set)
                            // read_before covers every `set:` column; an absent prior key was NULL in the row.
                            prior?.let { p -> row.copy(set = JsonObject(set.keys.associateWith { p[it] ?: JsonNull })) }
                        }
                        "transition" -> {
                            val back = prior?.get(requireNotNull(row.attribute))
                            if (back == null || back == JsonNull || back == row.to) {
                                null
                            } else {
                                row.copy(from = listOf((row.to as JsonPrimitive).content), to = back, history = true)
                            }
                        }
                        "insert" ->
                            if (row.seq in writerRows) {
                                ApplyRow(1, row.entity, "delete", key(row, entities))
                            } else {
                                null
                            }
                        else -> null
                    }
                if (inverse != null) out += inverse.copy(seq = out.size + 1, group = null)
            }
            return out
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
