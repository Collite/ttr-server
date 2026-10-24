// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A run's status (AU contracts §1.2 `GET /runs/{id}`); the vocabulary of `runs.status`. */
enum class RunStatus(
    val wire: String,
) {
    OPEN("open"),
    RUNNING("running"),
    EVALUATED("evaluated"),
    WAITING("waiting"),
    CLOSED("closed"),
    FAILED("failed"),
    ABANDONED("abandoned"),
    ;

    /** A key held by a run in this status is free for a new claim (D-46: failed ⇒ free · abandoned ⇒ free). */
    val freesKey: Boolean get() = this == FAILED || this == ABANDONED

    val terminal: Boolean get() = this == CLOSED || this == FAILED || this == ABANDONED

    /** The statuses this one may move to. `waiting` is left only by its last answer or `due` (D-97). */
    val next: Set<RunStatus>
        get() =
            when (this) {
                OPEN, RUNNING, EVALUATED -> setOf(RUNNING, EVALUATED, WAITING, CLOSED, FAILED, ABANDONED) - this
                WAITING -> setOf(CLOSED, FAILED)
                CLOSED, FAILED, ABANDONED -> emptySet()
            }

    companion object {
        fun of(wire: String): RunStatus = entries.firstOrNull { it.wire == wire } ?: error("unknown run status `$wire`")
    }
}

/** What [Ledger.claimRun] answers for an idempotency key (D-46). */
sealed interface Claim {
    val runId: UUID

    /** A new run was opened under the key. [abandoned] is the stale run the claim freed first, if any. */
    data class Claimed(
        override val runId: UUID,
        val abandoned: UUID? = null,
        /** The same instant written to runs.started_at, including transaction retry time. */
        val startedAt: Instant,
    ) : Claim

    /** The key's run is done: answer it again rather than run twice. */
    data class Replay(
        override val runId: UUID,
    ) : Claim

    /** The key's run is still live: hand the caller its handle to wait on. */
    data class Running(
        override val runId: UUID,
        val status: RunStatus,
    ) : Claim
}

/** The key's current holder, as the claim reads it: the one run per key that has not freed it. */
data class KeyHolder(
    val runId: UUID,
    val status: RunStatus,
    val startedAt: Instant,
    val budget: Duration,
)

/** The decision the claim acts on; [Claim] is what the caller then sees. */
sealed interface ClaimDecision {
    data object Open : ClaimDecision

    data class Replay(
        val runId: UUID,
    ) : ClaimDecision

    data class Wait(
        val runId: UUID,
        val status: RunStatus,
    ) : ClaimDecision

    data class AbandonThenOpen(
        val runId: UUID,
    ) : ClaimDecision
}

/**
 * D-46's exactly-one rule as a pure function: done ⇒ replay · live within its budget ⇒ wait on it · live past
 * budget + [GRACE] ⇒ abandon it and open anew · no holder (none, or only failed/abandoned runs) ⇒ open.
 * A `waiting` run is never abandoned by the clock (D-97): its open tasks hold it, however long they take.
 */
object IdempotencyPolicy {
    val GRACE: Duration = Duration.ofMinutes(2)

    fun decide(
        holder: KeyHolder?,
        now: Instant,
    ): ClaimDecision {
        if (holder == null || holder.status.freesKey) return ClaimDecision.Open
        return when (holder.status) {
            RunStatus.CLOSED -> ClaimDecision.Replay(holder.runId)
            RunStatus.WAITING -> ClaimDecision.Wait(holder.runId, holder.status)
            else ->
                if (now.isAfter(holder.startedAt + holder.budget + GRACE)) {
                    ClaimDecision.AbandonThenOpen(holder.runId)
                } else {
                    ClaimDecision.Wait(holder.runId, holder.status)
                }
        }
    }
}
