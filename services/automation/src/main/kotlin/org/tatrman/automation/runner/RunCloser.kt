// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.*
import org.tatrman.automation.writer.Apply
import org.tatrman.automation.writer.ApplyInput
import org.tatrman.automation.writer.ApplyJournal
import org.tatrman.automation.writer.RowOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@Serializable
data class CloseResult(
    val status: String,
    val batch: RunBatch,
    val protocolRef: String,
)

/**
 * Durable close first; then join local work before releasing DuckDB. Blob grace retention is separate.
 * A dry run closes its preview and sends nothing. An `auto` run (S2.2): plan (journaled at evaluate) → apply
 * through the door ([Apply]) → commit + seal. The apply needs the context pinned at Open (the run identity's
 * credential, the pinned entities and placement): once the executor is gone, an `auto` close refuses rather than
 * re-resolving a newer bundle. Closes of one run serialize; a retry never resends a request that may have
 * committed (the in-flight marker, [RowOutcome.UNKNOWN]). The run's original deadline holds through the apply and
 * the committed close (review-244 B2): past it the close answers `close-deadline-exceeded`, the batch stays `preview`
 * and every outcome already recorded stays.
 */
class RunCloser(
    private val ledger: Ledger,
    private val apply: Apply? = null,
    private val pinned: suspend (UUID, Caller) -> RunContext? = { _, _ -> null },
    private val release: suspend (UUID) -> Unit = {},
) {
    private class Gate {
        val mutex = Mutex()
        var users = 0 // Guarded by the registry lock.
    }

    private val gates = mutableMapOf<UUID, Gate>()
    private val registry = Mutex()

    suspend fun close(
        id: UUID,
        caller: Caller,
    ): CloseResult =
        withContext(NonCancellable) {
            val gate = registry.withLock { gates.getOrPut(id) { Gate() }.also { it.users++ } }
            val result =
                try {
                    gate.mutex.withLock { closeOnce(id, caller) }
                } finally {
                    registry.withLock { if (--gate.users == 0) gates.remove(id) }
                }
            // A cleanup failure leaves the committed close retryable; a retry attempts release again.
            release(id)
            result
        }

    private suspend fun closeOnce(
        id: UUID,
        caller: Caller,
    ): CloseResult {
        try {
            return io { ledger.closePreview(id, caller.sub) }
        } catch (_: WetRunClose) {
            // An auto run: apply first, then commit and seal.
        }
        val state = io { ledger.applyState(id, caller.sub) }
        check(!state.dryRun)
        if (state.status == RunStatus.EVALUATED) {
            val context =
                pinned(id, caller)
                    ?: throw AutomationException(
                        ErrorCode.CLOSE_NOT_READY,
                        "an auto run applies only from the context pinned at its Open, and that is no longer held",
                    )
            val metadata = requireNotNull(context.previewMetadata) { "preview metadata was not pinned at open" }
            val writer = requireNotNull(apply) { "this runtime has no writer" }
            writer.apply(
                ApplyInput(
                    id,
                    context.world,
                    context.identity,
                    context.modelFp,
                    // One absolute deadline: the run's original one, as pinned at Open and as the ledger holds it.
                    state.deadline,
                    state.plan.rows,
                    state.actions,
                    requireNotNull(metadata.planSource) { "apply placement was not pinned at open" },
                    metadata.entities,
                    context.recipe.recipe.roles
                        ?.routes
                        .orEmpty()
                        .map { it.name },
                    state.recorded,
                ),
                object : ApplyJournal {
                    override fun begin(seqs: List<Int>) = ledger.beginApply(state.batch, seqs)

                    override fun release(seqs: List<Int>) = ledger.releaseApply(state.batch, seqs)

                    override fun record(outcomes: List<RowOutcome>) = ledger.recordApply(state.batch, outcomes)
                },
            )
        }
        return io { ledger.closeCommitted(id, caller.sub) }
    }

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (_: NoSuchRun) {
                hidden()
            } catch (_: ProtocolSealed) {
                sealed()
            } catch (_: IllegalRunTransition) {
                throw AutomationException(
                    ErrorCode.CLOSE_NOT_READY,
                    "the run must finish evaluation before close",
                )
            } catch (_: NoRunBatch) {
                throw AutomationException(ErrorCode.CLOSE_NOT_READY, "the run has no completed preview batch")
            }
        }

    suspend fun authorizeExecution(
        id: UUID,
        caller: Caller,
    ) = withContext(Dispatchers.IO) {
        try {
            ledger.checkRunExecution(id, caller.sub)
        } catch (_: NoSuchRun) {
            hidden()
        } catch (_: ProtocolSealed) {
            sealed()
        }
    }

    private fun hidden(): Nothing =
        throw AutomationException(ErrorCode.IDENTITY, "the run is not available to this caller")

    private fun sealed(): Nothing = throw AutomationException(ErrorCode.RUN_CLOSED, "the run protocol is sealed")
}
