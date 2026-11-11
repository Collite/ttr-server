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

/** The run's absolute deadline: `started_at` + its batch → sync → default budget. At the deadline it has expired. */
internal fun runDeadline(
    run: UUID,
    defaultBudget: Duration,
): Instant {
    val row = Runs.selectAll().where { Runs.id eq run }.single()
    val budget = row[Runs.budgetBatch] ?: row[Runs.budgetSync] ?: defaultBudget.toMillis()
    return row[Runs.startedAt].toInstant().plusMillis(budget)
}
