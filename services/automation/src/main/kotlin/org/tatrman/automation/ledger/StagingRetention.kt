// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.config.RetentionConfig
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Called inside the execution transaction, after ownership and the run lock where applicable. */
internal fun ensureStagingAvailable(id: UUID) {
    val row = Runs.selectAll().where { Runs.id eq id }.single()
    if (row[Runs.stagingReleasedAt] != null) {
        throw AutomationException(ErrorCode.STAGING_RELEASED, "the run's staged outputs have been released")
    }
}

/** Two durable steps: fence publications, then delete blobs and mark the index released. */
open class StagingRetention(
    config: RetentionConfig,
    private val clock: Clock,
) {
    private val lifetime = Duration.ofHours(config.stagingHours)
    private val grace = Duration.ofHours(config.graceHours)

    init {
        require(config.stagingHours > 0 && config.graceHours >= 0) { "invalid staging retention hours" }
    }

    /** Revisit released runs fairly: late pre-fence blob puts must be collected on a later pass. */
    open fun candidates(limit: Int = 100): List<UUID> {
        require(limit in 1..1000)
        outermost()
        return transaction {
            val now = clock.instant()
            exec(
                """
                SELECT id FROM automation.runs
                WHERE staging_released_at IS NOT NULL OR status = 'abandoned'
                   OR started_at <= ?::timestamptz
                   OR (status = 'closed' AND ended_at <= ?::timestamptz)
                ORDER BY staging_checked_at NULLS FIRST, started_at, id LIMIT ?
                """.trimIndent(),
                listOf(
                    TextColumnType() to now.minus(lifetime).toString(),
                    TextColumnType() to now.minus(grace).toString(),
                    IntegerColumnType() to limit,
                ),
            ) { rs -> buildList { while (rs.next()) add(rs.getObject("id", UUID::class.java)) } }!!
        }
    }

    /** Commits before any executor cleanup or store IO. A crash cannot reopen this run's staging. */
    open fun fence(id: UUID): Boolean {
        outermost()
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val row =
                Runs
                    .selectAll()
                    .where { Runs.id eq id }
                    .forUpdate()
                    .singleOrNull() ?: return@transaction false
            val now = clock.instant()
            if (row[Runs.stagingReleasedAt] == null && !due(row, now)) return@transaction false
            Runs.update({ Runs.id eq id }) {
                if (row[Runs.stagingReleasedAt] == null) it[stagingReleasedAt] = now.atOffset(ZoneOffset.UTC)
                it[stagingCheckedAt] = now.atOffset(ZoneOffset.UTC)
            }
            true
        }
    }

    /**
     * Serialize competing sweepers with the publication lock. Store deletion is idempotent and limited to
     * the UUID prefix, never to a ledger-supplied path. Failure leaves the fence committed and index retryable.
     */
    open fun sweep(
        id: UUID,
        delete: (UUID) -> Unit,
    ) {
        outermost()
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            maxAttempts = 1 // External deletes may have happened already; the next maintenance pass retries.
            val row =
                Runs
                    .selectAll()
                    .where { Runs.id eq id }
                    .forUpdate()
                    .single()
            check(row[Runs.stagingReleasedAt] != null) { "staging must be fenced before deletion" }
            delete(id)
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            RunOutputs.update({ (RunOutputs.run eq id) and RunOutputs.releasedAt.isNull() }) { it[releasedAt] = now }
        }
    }

    private fun due(
        row: ResultRow,
        now: Instant,
    ): Boolean =
        row[Runs.status] == RunStatus.ABANDONED.wire ||
            !row[Runs.startedAt].toInstant().plus(lifetime).isAfter(now) ||
            (
                row[Runs.status] == RunStatus.CLOSED.wire &&
                    row[Runs.endedAt]?.toInstant()?.plus(grace)?.let { !it.isAfter(now) } == true
            )

    private fun outermost() =
        check(TransactionManager.currentOrNull() == null) {
            "staging retention requires its own transaction"
        }
}
