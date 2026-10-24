// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Call under the run row lock, after status/owner checks. Grace permits recovery, never late execution. */
internal fun ensureRunBudget(
    run: UUID,
    defaultBudget: Duration,
    clock: Clock,
) {
    val row = Runs.selectAll().where { Runs.id eq run }.single()
    val budget = row[Runs.budgetBatch] ?: row[Runs.budgetSync] ?: defaultBudget.toMillis()
    if (Duration.between(row[Runs.startedAt].toInstant(), clock.instant()) >= Duration.ofMillis(budget)) {
        throw AutomationException(ErrorCode.BUDGET, "the run exceeded its batch budget")
    }
}
