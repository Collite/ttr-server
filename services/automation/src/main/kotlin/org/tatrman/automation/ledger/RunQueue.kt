// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.queue.RunDelivery
import org.tatrman.automation.queue.RunWorkQueue
import java.sql.Connection
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * A durable trigger and a separate permanent execution claim, both serialized by the run row. Since D-142 the
 * trigger is the `run_evaluations` row itself (no queue): [read] takes the owner's oldest visible unclaimed ticket
 * `FOR UPDATE SKIP LOCKED`, so concurrent readers get distinct rows, and hides it for a visibility window; a ticket
 * whose claim never committed shows again after it. Visibility alone cannot promise at-most-once execution — the
 * claim does. A fresh process owner never adopts another process's context. All mutations need their own
 * transaction.
 */
class RunQueue(
    private val clock: Clock = Clock.systemUTC(),
    private val defaultBudget: java.time.Duration,
) : RunWorkQueue {
    override fun enqueue(
        run: UUID,
        owner: UUID,
    ) {
        outermost()
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(run)
            if (!executable(run)) unavailable()
            val existing = RunEvaluations.selectAll().where { RunEvaluations.run eq run }.firstOrNull()
            if (existing != null) {
                if (existing[RunEvaluations.owner] != owner) unavailable()
                ensureRunBudget(run, defaultBudget, clock)
            } else {
                ensureRunBudget(run, defaultBudget, clock)
                val now = clock.instant().atOffset(ZoneOffset.UTC)
                RunEvaluations.insert {
                    it[RunEvaluations.run] = run
                    it[RunEvaluations.owner] = owner
                    it[enqueuedAt] = now
                    it[visibleAt] = now
                }
            }
        }
    }

    override fun read(
        owner: UUID,
        visibilitySeconds: Int,
    ): RunDelivery? {
        outermost()
        require(visibilitySeconds > 0)
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val run =
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                exec(
                    """
                    UPDATE automation.run_evaluations SET visible_at = ?
                    WHERE run = (
                        SELECT run FROM automation.run_evaluations
                        WHERE owner = ? AND claimed_at IS NULL AND archived_at IS NULL AND visible_at <= ?
                        ORDER BY enqueued_at, run LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    RETURNING run
                    """.trimIndent(),
                    listOf<Pair<IColumnType<*>, Any>>(
                        JavaOffsetDateTimeColumnType() to now.plusSeconds(visibilitySeconds.toLong()),
                        UUIDColumnType() to owner,
                        JavaOffsetDateTimeColumnType() to now,
                    ),
                    StatementType.SELECT,
                ) { rs -> if (rs.next()) rs.getObject("run", UUID::class.java) else null }
            } ?: return null
        return RunDelivery(run, owner)
    }

    override fun claim(delivery: RunDelivery): Boolean {
        outermost()
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val run = delivery.run
            // Unknown/forged deliveries are harmless, not execution authority.
            val row =
                Runs
                    .selectAll()
                    .where { Runs.id eq run }
                    .forUpdate()
                    .firstOrNull() ?: return@transaction false
            val ticket =
                RunEvaluations.selectAll().where { RunEvaluations.run eq run }.firstOrNull()
                    ?: return@transaction false
            if (ticket[RunEvaluations.owner] != delivery.owner ||
                ticket[RunEvaluations.claimedAt] != null ||
                ticket[RunEvaluations.archivedAt] != null ||
                RunStatus.of(row[Runs.status]) !in setOf(RunStatus.OPEN, RunStatus.RUNNING) ||
                !executable(run)
            ) {
                return@transaction false
            }
            ensureRunBudget(run, defaultBudget, clock)
            RunEvaluations.update({ RunEvaluations.run eq run }) {
                it[claimedAt] =
                    clock.instant().atOffset(ZoneOffset.UTC)
            }
            Runs.update({ Runs.id eq run }) { it[status] = RunStatus.RUNNING.wire }
            true
        }
    }

    override fun archive(delivery: RunDelivery) {
        outermost()
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val ticket =
                RunEvaluations
                    .selectAll()
                    .where { RunEvaluations.run eq delivery.run }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction
            if (ticket[RunEvaluations.owner] == delivery.owner && ticket[RunEvaluations.archivedAt] == null) {
                RunEvaluations.update({ RunEvaluations.run eq delivery.run }) {
                    it[archivedAt] = clock.instant().atOffset(ZoneOffset.UTC)
                }
            }
        }
    }

    /** Reclaims deliveries of evaluated/terminal/waiting runs, including an owner lost on pod restart. */
    override fun archiveFinished(limit: Int): Int {
        outermost()
        require(limit in 1..1000)
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val tickets =
                exec(
                    """
                    SELECT e.run FROM automation.run_evaluations e
                    JOIN automation.runs r ON r.id = e.run
                    WHERE e.archived_at IS NULL AND r.status NOT IN ('open', 'running')
                    ORDER BY e.run LIMIT ? FOR UPDATE OF r SKIP LOCKED
                    """.trimIndent(),
                    listOf(IntegerColumnType() to limit),
                    StatementType.SELECT,
                ) { rs ->
                    buildList<UUID> {
                        while (rs.next()) add(rs.getObject("run", UUID::class.java))
                    }
                } ?: error("evaluation cleanup answered no result")
            var archived = 0
            tickets.forEach { run ->
                // Lock run before dispatch row, consistently with claim, and recheck a racing worker's ack.
                val ticket =
                    RunEvaluations
                        .selectAll()
                        .where { RunEvaluations.run eq run }
                        .forUpdate()
                        .single()
                if (ticket[RunEvaluations.archivedAt] == null) {
                    RunEvaluations.update({ RunEvaluations.run eq run }) {
                        it[archivedAt] = clock.instant().atOffset(ZoneOffset.UTC)
                    }
                    archived++
                }
            }
            archived
        }
    }

    private fun lockRun(run: UUID) {
        Runs
            .select(Runs.id)
            .where { Runs.id eq run }
            .forUpdate()
            .firstOrNull() ?: throw NoSuchRun(run)
    }

    private fun executable(run: UUID): Boolean {
        val state = RunStatus.of(Runs.select(Runs.status).where { Runs.id eq run }.single()[Runs.status])
        if (state !in setOf(RunStatus.OPEN, RunStatus.RUNNING)) return false
        val last =
            RunProtocol
                .selectAll()
                .where {
                    (RunProtocol.run eq run) and (RunProtocol.section eq ProtocolSection.AUTOMATION.wire)
                }.orderBy(RunProtocol.seq, org.jetbrains.exposed.v1.core.SortOrder.DESC)
                .limit(1)
                .firstOrNull()
        return ((last?.get(RunProtocol.payload) as? JsonObject)?.get("sealed") as? JsonPrimitive)?.booleanOrNull != true
    }

    private fun outermost() =
        check(TransactionManager.currentOrNull() == null) { "RunQueue requires its own transaction" }

    private fun unavailable(): Nothing =
        throw AutomationException(ErrorCode.RUN_ABANDONED, "run evaluation is unavailable")
}
