// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.IllegalRunTransition
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.NoRunBatch
import org.tatrman.automation.ledger.NoSuchRun
import org.tatrman.automation.ledger.ProtocolSealed
import org.tatrman.automation.ledger.RunStatus
import java.util.UUID

@Serializable
data class PerformRequest(
    val keys: List<JsonObject>? = null,
    val group: JsonElement? = null,
)

@Serializable
data class Performed(
    val key: JsonObject,
    val batchRows: List<Int>,
)

@Serializable
data class SkippedKey(
    val key: JsonObject,
    val reason: String,
)

/** §1.2 as amended 2026-11-03: `{ performed: [{ key, batchRows }], skipped }` — no `taskId`. */
@Serializable
data class PerformResponse(
    val performed: List<Performed>,
    val skipped: List<SkippedKey>,
)

@Serializable
data class OutcomeItem(
    val key: JsonObject,
    val status: String,
    val at: String,
    val detail: JsonElement? = null,
)

@Serializable
data class OutcomeRequest(
    val outcomes: List<OutcomeItem>,
)

/**
 * S2.3 `perform` / `outcome` (§1.2, Amendment 2026-11-03). `perform` applies, ahead of the close, only the rows of one
 * action for the keys given (all its keys when none are), through [Apply] under the run's pinned context; it is
 * idempotent per key through `batch_rows.status`, so a second identical call answers the first outcome and sends
 * nothing, and the close never resends a performed row. It never bypasses an approval: a held row's key is skipped
 * (`held`). A dry run performs nothing (`dry-run`). It shares the close's per-run gate. `outcome` appends the engine's
 * per-key delivery outcomes to the `actions` protocol section (no `deliveries` row); a sealed protocol refuses.
 */
class Perform(
    private val ledger: Ledger,
    private val apply: Apply,
    private val pinned: suspend (UUID, Caller) -> ApplyContext?,
    private val exclusive: suspend (UUID, suspend () -> PerformResponse) -> PerformResponse,
) {
    suspend fun perform(
        run: UUID,
        action: String,
        request: PerformRequest,
        caller: Caller,
    ): PerformResponse =
        exclusive(run) {
            val state = io { ledger.applyState(run, caller.sub) }
            val mine = state.actions.filter { it.action == action }
            if (mine.isEmpty()) {
                throw AutomationException(ErrorCode.BAD_REQUEST, "the run has no rows of action $action")
            }
            val wanted = request.keys?.toSet()
            val selected = mine.filter { wanted == null || it.key in wanted }
            val unknown = wanted.orEmpty() - selected.map { it.key }.toSet()
            val rows = state.plan.rows.associateBy { it.seq }
            val held = selected.filter { a -> a.batchRows.any { rows[it]?.group != null } }
            val pending =
                (selected - held.toSet())
                    .flatMap { it.batchRows }
                    .distinct()
                    .filter { it !in state.recorded }
                    .mapNotNull { rows[it] }
            if (pending.isNotEmpty() && !state.dryRun) {
                if (state.status != RunStatus.EVALUATED) {
                    throw AutomationException(ErrorCode.CLOSE_NOT_READY, "rows apply only before the run's close")
                }
                val context =
                    pinned(run, caller) ?: throw AutomationException(
                        ErrorCode.CLOSE_NOT_READY,
                        "perform applies only from the context pinned at the run's Open, and that is no longer held",
                    )
                apply.apply(
                    ApplyInput(
                        run,
                        context.world,
                        context.identity,
                        context.modelFp,
                        state.deadline,
                        pending,
                        selected,
                        context.source,
                        context.entities,
                        context.routes,
                        state.recorded,
                    ),
                    object : ApplyJournal {
                        override fun begin(seqs: List<Int>) = ledger.beginApply(state.batch, seqs)

                        override fun release(seqs: List<Int>) = ledger.releaseApply(state.batch, seqs)

                        override fun record(outcomes: List<RowOutcome>) = ledger.recordApply(state.batch, outcomes)
                    },
                )
            }
            val statuses = io { ledger.readRunBatch(run, caller.sub) }.statuses.associateBy { it.seq }
            val performed = mutableListOf<Performed>()
            val skipped = unknown.map { SkippedKey(it, "not-a-key") }.toMutableList()
            for (a in selected) {
                val reason =
                    when {
                        a in held -> "held"
                        state.dryRun -> "dry-run"
                        a.batchRows.any { statuses[it]?.status == "failed" } -> "failed"
                        a.batchRows.any { statuses[it]?.status == "staged" } -> "not-applied"
                        else -> null
                    }
                if (reason == null) performed += Performed(a.key, a.batchRows) else skipped += SkippedKey(a.key, reason)
            }
            PerformResponse(performed, skipped)
        }

    suspend fun outcome(
        run: UUID,
        action: String,
        request: OutcomeRequest,
        caller: Caller,
    ): JsonObject {
        request.outcomes.forEach {
            if (it.status !in OUTCOMES) {
                throw AutomationException(ErrorCode.BAD_REQUEST, "an outcome is one of ${OUTCOMES.sorted()}")
            }
        }
        val state = io { ledger.applyState(run, caller.sub) }
        val keys =
            state.actions
                .filter { it.action == action }
                .map { it.key }
                .toSet()
        if (keys.isEmpty()) throw AutomationException(ErrorCode.BAD_REQUEST, "the run has no rows of action $action")
        request.outcomes.firstOrNull { it.key !in keys }?.let {
            throw AutomationException(ErrorCode.BAD_REQUEST, "${it.key} is not a key of action $action in this run")
        }
        val payload =
            buildJsonObject {
                put("event", "outcome")
                put("action", action)
                put(
                    "outcomes",
                    kotlinx.serialization.json.JsonArray(
                        request.outcomes.map {
                            buildJsonObject {
                                put("key", it.key)
                                put("status", it.status)
                                put("at", it.at)
                                it.detail?.let { d -> put("detail", d) }
                            }
                        },
                    ),
                )
            }
        io { ledger.appendOutcome(run, caller.sub, payload) }
        return buildJsonObject {
            put("action", action)
            put("outcomes", JsonPrimitive(request.outcomes.size))
        }
    }

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (_: NoSuchRun) {
                throw AutomationException(ErrorCode.IDENTITY, "the run is not available to this caller")
            } catch (_: NoRunBatch) {
                throw AutomationException(ErrorCode.CLOSE_NOT_READY, "the run has no completed preview batch")
            } catch (_: IllegalRunTransition) {
                throw AutomationException(ErrorCode.CLOSE_NOT_READY, "the run must finish evaluation first")
            } catch (_: ProtocolSealed) {
                throw AutomationException(ErrorCode.RUN_CLOSED, "the run protocol is sealed")
            }
        }

    companion object {
        val OUTCOMES = setOf("delivered", "failed", "skipped")
    }
}
