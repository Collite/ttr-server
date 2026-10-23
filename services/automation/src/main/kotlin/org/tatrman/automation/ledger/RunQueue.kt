// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.queue.*
import java.sql.Connection
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * A durable trigger and a separate permanent execution claim, both serialized by the run row. pgmq visibility
 * alone cannot promise at-most-once execution. A fresh process owner never adopts another process's context.
 * All mutations need their own transaction; enqueue + dispatch journal either both commit or neither does.
 */
class RunQueue(
    private val pgmq: Pgmq = Pgmq(),
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
                val message =
                    buildJsonObject {
                        put("run", run.toString())
                        put("owner", owner.toString())
                    }
                val id = pgmq.send(LedgerQueue.RUNS, message)
                RunEvaluations.insert {
                    it[RunEvaluations.run] = run
                    it[RunEvaluations.owner] = owner
                    it[messageId] = id
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
        val message =
            pgmq
                .read(
                    LedgerQueue.RUNS,
                    visibilitySeconds,
                    conditional = buildJsonObject { put("owner", owner.toString()) },
                ).singleOrNull() ?: return null
        return try {
            val body = message.message.jsonObject
            val run = requireNotNull(body["run"]) { "missing run handle" }.jsonPrimitive.content
            RunDelivery(message.msgId, UUID.fromString(run).also { require(it.toString() == run) }, owner)
        } catch (e: IllegalArgumentException) {
            pgmq.archive(LedgerQueue.RUNS, message.msgId) // Poison delivery cannot execute or block the owner's queue.
            throw e
        }
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
                ticket[RunEvaluations.messageId] != delivery.messageId ||
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
            if (ticket[RunEvaluations.owner] == delivery.owner &&
                ticket[RunEvaluations.messageId] == delivery.messageId
            ) {
                pgmq.archive(LedgerQueue.RUNS, delivery.messageId)
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
                    SELECT e.run, e.message_id FROM automation.run_evaluations e
                    JOIN automation.runs r ON r.id = e.run
                    WHERE e.archived_at IS NULL AND r.status NOT IN ('open', 'running')
                    ORDER BY e.run LIMIT ? FOR UPDATE OF r SKIP LOCKED
                    """.trimIndent(),
                    listOf(IntegerColumnType() to limit),
                    StatementType.SELECT,
                ) { rs ->
                    buildList<Pair<UUID, Long>> {
                        while (rs.next()) add(rs.getObject("run", UUID::class.java) to rs.getLong("message_id"))
                    }
                } ?: error("evaluation cleanup answered no result")
            var archived = 0
            tickets.forEach { (run, id) ->
                // Lock run before dispatch row, consistently with claim, and recheck a racing worker's ack.
                val ticket =
                    RunEvaluations
                        .selectAll()
                        .where { RunEvaluations.run eq run }
                        .forUpdate()
                        .single()
                if (ticket[RunEvaluations.archivedAt] == null) {
                    pgmq.archive(LedgerQueue.RUNS, id)
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
