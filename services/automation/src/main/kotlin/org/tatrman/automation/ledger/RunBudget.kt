// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Call under the run row lock, after status/owner checks. Grace permits recovery, never late execution. A wet
 * close ([wetClose]) answers `close-deadline-exceeded` (Amendment 2026-11-11, review-244 B2), everything else `budget`.
 */
internal fun ensureRunBudget(
    run: UUID,
    defaultBudget: Duration,
    clock: Clock,
    wetClose: Boolean = false,
) {
    if (!clock.instant().isBefore(runDeadline(run, defaultBudget))) {
        if (wetClose) {
            throw AutomationException(
                ErrorCode.CLOSE_DEADLINE_EXCEEDED,
                "the run's original deadline passed before its wet close committed",
            )
        }
        throw AutomationException(ErrorCode.BUDGET, "the run exceeded its batch budget")
    }
}

/**
 * The run's absolute deadline: `started_at` + its batch → sync → default budget. At the deadline it has expired. The
 * deadline is not the abandonment clock: D-192 moves only the sweep and the claim's expiry onto [runBudget].
 */
internal fun runDeadline(
    run: UUID,
    defaultBudget: Duration,
): Instant {
    val row = Runs.selectAll().where { Runs.id eq run }.single()
    val budget = runBudget(null, row[Runs.budgetSync], row[Runs.budgetBatch], defaultBudget)
    return row[Runs.startedAt].toInstant().plus(budget)
}

/** The program mode whose runs are clocked by `budget_sync` (D-192); every other mode is clocked by `budget_batch`. */
internal const val ON_EVENT_MODE = "on_event"

/**
 * The one budget rule of the run clock (D-192, contracts "Amendment 2026-11-14"): an `on_event` run is clocked by its
 * sync → batch → default budget, every other run (and every pre-V10 row, mode null) by batch → sync → default. The
 * claim's expiry and the abandonment sweep read it, so they never disagree about when a key frees.
 */
internal fun runBudget(
    mode: String?,
    syncMs: Long?,
    batchMs: Long?,
    defaultBudget: Duration,
): Duration {
    val ms = if (mode == ON_EVENT_MODE) syncMs ?: batchMs else batchMs ?: syncMs
    return ms?.let(Duration::ofMillis) ?: defaultBudget
}
