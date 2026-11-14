// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.runner.EvaluationResult
import org.tatrman.automation.runner.RunProgress
import org.tatrman.automation.runner.RunFingerprints
import org.tatrman.automation.runner.RunBatch
import org.tatrman.automation.runner.CloseResult
import org.tatrman.automation.apply.*
import org.tatrman.automation.apply.ApplyRow.Companion.DECIDED
import org.tatrman.automation.apply.ApplyRow.Companion.HISTORY_AT
import org.tatrman.automation.apply.ApplyRow.Companion.HISTORY_OUTCOME
import org.tatrman.automation.protocol.IslandExecution
import org.tatrman.automation.protocol.ProtocolDocument
import org.tatrman.automation.protocol.ProtocolEntry
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The nine sections of a protocol/v1 document (AU contracts §5; D-70). */
enum class ProtocolSection(
    val wire: String,
) {
    AUTOMATION("automation"),
    ISLANDS("islands"),
    DECISION("decision"),
    ACTIONS("actions"),
    WRITES("writes"),
    PEOPLE("people"),
    TURNS("turns"),
    SIGNALS("signals"),
    RECEIPTS("receipts"),
}

/** What opening a run records (AU contracts §2 `runs`). The fingerprints are the approved-against set (D-60). */
data class RunOpen(
    val recipe: String,
    val program: String? = null,
    val deployment: String? = null,
    val bundle: String? = null,
    val fpText: String? = null,
    val fpValues: String? = null,
    val fpProgram: String? = null,
    val fpDeployment: String? = null,
    val modelFp: String? = null,
    val port: JsonElement = JsonObject(emptyMap()),
    val asOf: Instant? = null,
    val author: String? = null,
    val runAs: String? = null,
    val caller: String? = null,
    val origin: String? = null,
    val dryRun: Boolean = false,
    val governed: Boolean = true,
    val idempotencyKey: String? = null,
    val replayOf: UUID? = null,
    val budgetSync: Duration? = null,
    val budgetBatch: Duration? = null,
    /** The program's `mode` (`batch · on_event · windowed`); `on_event` clocks the run by [budgetSync] (D-192). */
    val mode: String? = null,
    /** `recipe · query · perform` (S3.1). */
    val kind: String = RUN_KIND_RECIPE,
    /** Written in the same transaction as a new claim; never appended again for a replay/live handle. */
    val openingProtocol: JsonElement? = null,
    /** Verified JWT subject; never infer this from the caller's display principal. Legacy rows stay null. */
    val callerSubject: String? = null,
)

const val RUN_KIND_RECIPE = "recipe"
const val RUN_KIND_QUERY = "query"
const val RUN_KIND_PERFORM = "perform"

/** One row of `GET /automation/runs` (S3.1 T4). [subject] is the recipe, the query's subject or the action. */
data class RunListing(
    val id: UUID,
    val kind: String,
    val subject: String,
    val program: String?,
    val deployment: String?,
    val status: RunStatus,
    val origin: String?,
    val dryRun: Boolean,
    val startedAt: Instant,
    val endedAt: Instant?,
    val protocolRef: String,
    val error: String?,
)

/** A persisted run handle. The caller's token and execution resources never enter the ledger. */
data class RunRecord(
    val id: UUID,
    val open: RunOpen,
    val status: RunStatus,
    val protocolRef: String?,
)

/** A staged output (`run_outputs`). */
data class RunOutput(
    val run: UUID,
    val island: String,
    val port: String,
    val rows: Long,
    val bytes: Long,
    val path: String?,
    val sha256: String?,
)

/** What an `ask` (or another outbound task) opens (`tasks`; D-97 snapshot, D-98 one open, D-101 run nullable). */
data class TaskOpen(
    val run: UUID?,
    val recipe: String?,
    val key: JsonElement?,
    val kind: String,
    val action: String?,
    val targetRole: String?,
    val targetPerson: String?,
    val about: String?,
    val options: JsonElement?,
    val detail: JsonElement?,
    val due: Instant?,
    val dryRun: Boolean,
    val origin: String?,
)

class NoSuchRun(
    val runId: UUID,
) : RuntimeException("no run `$runId`")

class NoRunBatch(
    val runId: UUID,
) : RuntimeException("run has no batch")

class IllegalRunTransition(
    val runId: UUID,
    val from: RunStatus,
    val to: RunStatus,
) : RuntimeException("run `$runId` cannot move from ${from.wire} to ${to.wire}")

/** The run is wet (`auto`): its close applies the batch first (S2.2), so the preview close does not take it. */
class WetRunClose(
    val runId: UUID,
) : RuntimeException("run $runId is an auto run")

class ProtocolSealed(
    val runId: UUID,
) : RuntimeException("the protocol of run `$runId` is sealed (D-110)")

/** S2.3: the run's batch holds no row of this group. */
class NoHeldGroup(
    val runId: UUID,
    val group: JsonObject,
) : RuntimeException("run `$runId` holds no group $group")

/** S2.3: what a held-group decision reads (see [Ledger.heldGroup]). */
data class HeldGroup(
    val run: UUID,
    val batch: UUID,
    val status: RunStatus,
    val batchState: String,
    val names: RunNames,
    val fingerprints: RunFingerprints,
    val rows: List<ApplyRow>,
    val statuses: List<BatchRowStatus>,
    val actions: List<DryRunAction>,
    val budget: Duration,
)

/** S2.3: what a revert reads (see [Ledger.revertState]). */
data class RevertState(
    val run: UUID,
    val batch: UUID,
    val names: RunNames,
    val plan: RunBatchPlan,
    val actions: List<DryRunAction>,
    /** A revert batch an interrupted attempt already journaled. */
    val rev: RunBatchPlan?,
    val budget: Duration,
)

/** S2.3: the names a run pinned at Open — what a deferred apply re-resolves and D-99 compares. */
data class RunNames(
    val recipe: String,
    val program: String?,
    val deployment: String?,
    val bundle: String?,
    val runAs: String?,
)

/** S2.3: one decision's answer (the dev routes' `{ batch, group, applied | discarded }`). */
data class HeldDecision(
    val batch: String,
    val group: JsonObject,
    val decision: String,
    val applied: Int,
    val discarded: Int,
    val runStatus: String,
)

/**
 * The ledger (AU contracts §2) — every write the service makes to schema `automation`. The operations the
 * later stages need are named here with their signatures, so the API shape is fixed from S0.1; the ones a
 * later stage implements are `TODO("S<n.m>")`.
 *
 * Each operation is one Exposed transaction on the default database (the one [shared.libs.db.common.
 * DatabaseConnection] connected). [defaultBudget] is the batch budget a run without its own is held to
 * (`automation.budgets.batchDefault`). [abandoned] counts each run the claim abandons, by its recipe
 * (`automation_abandoned_total`, §10).
 *
 * Claim, evaluation begin/completion, output publication, abandonment sweep and protocol append require an
 * outermost transaction: inside a caller's `transaction {}` Exposed would join the outer one, and their retry
 * and isolation — what makes them correct under concurrency — would silently not apply. They refuse to run
 * there ([IllegalStateException]).
 */
class Ledger(
    private val defaultBudget: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val abandoned: (recipe: String) -> Unit = {},
) {
    /**
     * Opens a run, or answers the key's existing one (D-46). Without a key every call opens a run.
     *
     * Two claims of one fresh key race on the partial unique index `runs_idempotency_key_claimed`: the loser's
     * INSERT fails, Exposed retries the whole block in a new transaction, and the retry reads the winner as
     * the key's holder. Hence [CLAIM_ATTEMPTS] — one try plus the retry a race needs, plus one spare. A retry
     * needs a transaction of its own, so the claim never runs inside another.
     */
    fun claimRun(open: RunOpen): Claim {
        outermost("claimRun")
        var abandonedRecipe: String? = null
        val claim =
            transaction {
                maxAttempts = CLAIM_ATTEMPTS
                abandonedRecipe = null
                val now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                val key = open.idempotencyKey ?: return@transaction Claim.Claimed(insertRun(open, now), startedAt = now)
                val row =
                    Runs
                        .selectAll()
                        .where { (Runs.idempotencyKey eq key) and (Runs.status notInList FREE_STATUSES) }
                        .forUpdate()
                        .firstOrNull()
                val holder =
                    row?.let {
                        KeyHolder(
                            runId = it[Runs.id],
                            status = RunStatus.of(it[Runs.status]),
                            startedAt = it[Runs.startedAt].toInstant(),
                            budget = runBudget(it[Runs.mode], it[Runs.budgetSync], it[Runs.budgetBatch], defaultBudget),
                        )
                    }
                when (val decision = IdempotencyPolicy.decide(holder, now)) {
                    ClaimDecision.Open -> Claim.Claimed(insertRun(open, now), startedAt = now)
                    is ClaimDecision.Replay -> Claim.Replay(decision.runId)
                    is ClaimDecision.Wait -> Claim.Running(decision.runId, decision.status)
                    is ClaimDecision.AbandonThenOpen -> {
                        Runs.update({ Runs.id eq decision.runId }) {
                            it[status] = RunStatus.ABANDONED.wire
                            it[endedAt] = now.utc()
                            it[error] = JsonObject(mapOf("code" to JsonPrimitive("run-abandoned")))
                        }
                        abandonedRecipe = row?.get(Runs.recipe)
                        Claim.Claimed(insertRun(open, now), abandoned = decision.runId, startedAt = now)
                    }
                }
            }
        abandonedRecipe?.let(abandoned)
        return claim
    }

    /** Moves a run along [RunStatus.next]; a terminal status stamps `ended_at`. [error] lands in `runs.error`. */
    fun markStatus(
        runId: UUID,
        to: RunStatus,
        error: JsonElement? = null,
    ) {
        transaction {
            val from =
                Runs
                    .select(Runs.status)
                    .where { Runs.id eq runId }
                    .forUpdate()
                    .firstOrNull()
                    ?.let { RunStatus.of(it[Runs.status]) }
                    ?: throw NoSuchRun(runId)
            if (to !in from.next) throw IllegalRunTransition(runId, from, to)
            Runs.update({ Runs.id eq runId }) {
                it[status] = to.wire
                if (to.terminal) it[endedAt] = clock.instant().utc()
                if (error != null) it[Runs.error] = error
            }
        }
    }

    /** Idempotent begin: a retry cannot regress a completed decision or resurrect a terminal/waiting run. */
    fun startEvaluation(runId: UUID) {
        outermost("startEvaluation")
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            val from = RunStatus.of(Runs.select(Runs.status).where { Runs.id eq runId }.single()[Runs.status])
            if (from !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) {
                throw IllegalRunTransition(runId, from, RunStatus.RUNNING)
            }
            if (isSealed(runId)) throw ProtocolSealed(runId)
            ensureStagingAvailable(runId)
            if (from != RunStatus.EVALUATED) ensureRunBudget(runId, defaultBudget, clock)
            if (from == RunStatus.OPEN) Runs.update({ Runs.id eq runId }) { it[status] = RunStatus.RUNNING.wire }
        }
    }

    /**
     * Executor failure, conditional on an executing status. Repeated/concurrent reports are harmless and cannot
     * replace CLOSED/ABANDONED/WAITING (e.g. another claimant already abandoned this run). The caller may retry
     * a failed journal attempt. No exception text or bearer is stored by default.
     */
    fun failActiveRun(
        runId: UUID,
        error: JsonElement? = null,
    ): Boolean =
        transaction {
            Runs.update({
                (Runs.id eq runId) and
                    (Runs.status inList listOf(RunStatus.OPEN.wire, RunStatus.RUNNING.wire, RunStatus.EVALUATED.wire))
            }) {
                it[status] = RunStatus.FAILED.wire
                it[endedAt] = clock.instant().utc()
                it[Runs.error] = error ?: JsonObject(mapOf("code" to JsonPrimitive("internal")))
            } == 1
        }

    /** Session failure callback: preserve the public code without storing exception text or credentials. */
    fun failExecution(
        runId: UUID,
        cause: Exception,
    ) {
        val code = (cause as? org.tatrman.automation.api.AutomationException)?.code?.code ?: "internal"
        failActiveRun(runId, JsonObject(mapOf("code" to JsonPrimitive(code))))
    }

    /**
     * Commit evaluation as one transaction: immutable decision evidence and EVALUATED status. The decision
     * section is the run's durable verdict summary; no second mutable copy can drift from the protocol.
     * Retries accept the identical payload only. A late worker cannot resurrect an abandoned/failed/closed run.
     */
    fun completeEvaluation(
        runId: UUID,
        result: EvaluationResult,
    ) = completeEvaluationStep(runId, result, null)

    /** P1 runtime completion: preview, action/write evidence, decision and status commit together. */
    fun completeEvaluation(
        runId: UUID,
        result: EvaluationResult,
        preview: PreviewPlan,
    ) = completeEvaluationStep(runId, result, preview)

    private fun completeEvaluationStep(
        runId: UUID,
        result: EvaluationResult,
        preview: PreviewPlan?,
    ) {
        outermost("completeEvaluation")
        require(preview == null || preview.run == runId.toString()) { "preview belongs to another run" }
        preview?.let { require(UUID.fromString(it.batch).toString() == it.batch) { "invalid preview batch id" } }
        val payload = Json.encodeToJsonElement(result)
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            val from = RunStatus.of(Runs.select(Runs.status).where { Runs.id eq runId }.single()[Runs.status])
            if (from !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) {
                throw IllegalRunTransition(runId, from, RunStatus.EVALUATED)
            }
            if (isSealed(runId)) throw ProtocolSealed(runId)
            ensureStagingAvailable(runId)
            val existing =
                RunProtocol
                    .selectAll()
                    .where {
                        (RunProtocol.run eq runId) and (RunProtocol.section eq ProtocolSection.DECISION.wire)
                    }.toList()
            if (existing.isNotEmpty()) {
                check(
                    existing.size == 1 &&
                        sameJson(existing.single()[RunProtocol.payload], payload) &&
                        from == RunStatus.EVALUATED,
                ) {
                    "a pinned run's decision cannot change"
                }
                preview?.let { checkPreview(runId, it) }
            } else {
                ensureRunBudget(runId, defaultBudget, clock)
                preview?.let {
                    insertPreview(runId, it)
                    ensureRunBudget(runId, defaultBudget, clock)
                }
                RunProtocol.insert {
                    it[run] = runId
                    it[section] = ProtocolSection.DECISION.wire
                    it[seq] = 1
                    it[RunProtocol.payload] = payload
                    it[at] = clock.instant().utc()
                }
                Runs.update({ Runs.id eq runId }) { it[status] = RunStatus.EVALUATED.wire }
            }
        }
    }

    /** Held run lock, same transaction as the decision. No model tables or outbound task rows are touched. */
    private fun insertPreview(
        runId: UUID,
        preview: PreviewPlan,
    ) {
        val runRow = Runs.selectAll().where { Runs.id eq runId }.single()
        // S2.2: a wet run journals the same staged plan; its close applies it (contracts §4 `auto`).
        check(runRow[Runs.batchId] == null) { "a preview requires an unbatched run" }
        check(Batches.selectAll().where { Batches.run eq runId }.empty()) { "a run already has a batch" }
        val id = UUID.fromString(preview.batch)
        Batches.insert {
            it[Batches.id] = id
            it[Batches.run] = runId
            it[state] = "preview"
            it[rows] = preview.rows.size
        }
        BatchRows.batchInsert(preview.rows, shouldReturnGeneratedValues = false) { item ->
            this[BatchRows.batch] = id
            this[BatchRows.seq] = item.seq
            this[BatchRows.entity] = item.entity
            this[BatchRows.op] = item.op
            this[BatchRows.key] = item.key
            this[BatchRows.row] = item.storedRow()
            this[BatchRows.groupKey] = item.group
            this[BatchRows.status] = "staged"
        }
        val at = clock.instant().utc()
        val first = nextSeq(runId, ProtocolSection.ACTIONS)
        RunProtocol.batchInsert(
            preview.actions.withIndex().toList(),
            shouldReturnGeneratedValues = false,
        ) { (index, action) ->
            this[RunProtocol.run] = runId
            this[RunProtocol.section] = ProtocolSection.ACTIONS.wire
            this[RunProtocol.seq] = first + index
            this[RunProtocol.payload] = action.payload()
            this[RunProtocol.at] = at
        }
        RunProtocol.insert {
            it[run] = runId
            it[section] = ProtocolSection.WRITES.wire
            it[seq] = nextSeq(runId, ProtocolSection.WRITES)
            it[payload] = Json.encodeToJsonElement(preview.evidence())
            it[RunProtocol.at] = at
        }
        Runs.update({ Runs.id eq runId }) { it[batchId] = id }
    }

    private fun checkPreview(
        runId: UUID,
        preview: PreviewPlan,
    ) {
        val id = Runs.select(Runs.batchId).where { Runs.id eq runId }.single()[Runs.batchId]
        check(id?.toString() == preview.batch) { "a pinned run's preview cannot change" }
        val stored = batchPlan(runId, requireNotNull(id))
        check(
            stored.rows.size == preview.rows.size &&
                stored.rows.zip(preview.rows).all { (a, b) ->
                    a.seq == b.seq &&
                        a.entity == b.entity &&
                        a.op == b.op &&
                        sameJson(a.key, b.key) &&
                        sameJson(a.group, b.group) &&
                        sameJson(a.storedRow(), b.storedRow())
                },
        ) {
            "a pinned run's preview rows cannot change"
        }
        val actions = previewActions(runId)
        check(
            actions.size == preview.actions.size &&
                actions.zip(preview.actions).all { (a, b) -> sameJson(a.payload(), b.payload()) },
        ) {
            "a pinned run's action preview cannot change"
        }
        val writes =
            RunProtocol
                .select(RunProtocol.payload)
                .where { (RunProtocol.run eq runId) and (RunProtocol.section eq ProtocolSection.WRITES.wire) }
                .toList()
        check(
            writes.size == 1 &&
                sameJson(writes.single()[RunProtocol.payload], Json.encodeToJsonElement(preview.evidence())),
        ) {
            "a pinned run's write preview cannot change"
        }
    }

    private fun previewActions(runId: UUID): List<DryRunAction> =
        RunProtocol
            .selectAll()
            .where { (RunProtocol.run eq runId) and (RunProtocol.section eq ProtocolSection.ACTIONS.wire) }
            .orderBy(RunProtocol.seq to SortOrder.ASC)
            .mapNotNull { entry ->
                val payload = entry[RunProtocol.payload]
                if ((payload as? JsonObject)?.get("event") ==
                    JsonPrimitive("preview")
                ) {
                    Json.decodeFromJsonElement<DryRunAction>(payload)
                } else {
                    null
                }
            }

    private fun batchPlan(
        runId: UUID,
        id: UUID,
    ): RunBatchPlan {
        val batch = Batches.selectAll().where { (Batches.id eq id) and (Batches.run eq runId) }.single()
        val mode =
            when (batch[Batches.state]) {
                "preview" -> "preview"
                "committed", "reverted" -> "auto"
                "held" -> "hold"
                else -> error("unknown batch state ${batch[Batches.state]}")
            }
        val stored =
            BatchRows
                .selectAll()
                .where { BatchRows.batch eq id }
                .orderBy(
                    BatchRows.seq to SortOrder.ASC,
                ).toList()
        val rows =
            stored.map {
                ApplyRow.restore(
                    it[BatchRows.seq],
                    it[BatchRows.entity],
                    it[BatchRows.op],
                    it[BatchRows.key],
                    it[BatchRows.row],
                    it[BatchRows.groupKey],
                )
            }
        check(rows.size == batch[Batches.rows]) { "batch row count differs from its header" }
        check(rows.map { it.seq } == (1..rows.size).toList()) { "batch sequence is not contiguous" }
        val statuses =
            stored.map {
                BatchRowStatus(
                    it[BatchRows.seq],
                    it[BatchRows.status],
                    it[BatchRows.error],
                    (it[BatchRows.row] as? JsonObject)?.get("prior") as? JsonObject,
                    ((it[BatchRows.row] as? JsonObject)?.get(HISTORY_OUTCOME) as? JsonPrimitive)?.content,
                    ((it[BatchRows.row] as? JsonObject)?.get(HISTORY_AT) as? JsonPrimitive)?.content,
                    (it[BatchRows.row] as? JsonObject)?.get(DECIDED) as? JsonObject,
                    (
                        (it[BatchRows.row] as? JsonObject)?.get(
                            org.tatrman.automation.apply.ApplyRow.REQUEST,
                        ) as? JsonPrimitive
                    )?.content,
                )
            }
        return RunBatchPlan(id.toString(), runId.toString(), rows, mode, statuses)
    }

    /**
     * S2.2: what the close of an `auto` run applies — the journaled plan, its action evidence (an action's `set:` and
     * `transition` are one compare-and-set) and every outcome an earlier close attempt recorded. The run must still be
     * within its budget before any row leaves; a closed run answers its committed state for an idempotent retry.
     * [ApplyState.deadline] is the original absolute deadline: every request of the apply is bounded by what remains.
     */
    fun applyState(
        runId: UUID,
        subject: String,
    ): ApplyState {
        require(subject.isNotBlank())
        outermost("applyState")
        return transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val row =
                Runs
                    .selectAll()
                    .where { (Runs.id eq runId) and (Runs.callerSubject eq subject) }
                    .firstOrNull() ?: throw NoSuchRun(runId)
            val status = RunStatus.of(row[Runs.status])
            if (status !in setOf(RunStatus.EVALUATED, RunStatus.CLOSED, RunStatus.WAITING)) {
                throw IllegalRunTransition(runId, status, RunStatus.CLOSED)
            }
            val batchId = row[Runs.batchId] ?: throw NoRunBatch(runId)
            if (status == RunStatus.EVALUATED && !row[Runs.dryRun]) {
                if (isSealed(runId)) throw ProtocolSealed(runId)
                ensureRunBudget(runId, defaultBudget, clock, wetClose = true)
            }
            val plan = batchPlan(runId, batchId)
            ApplyState(
                batchId,
                row[Runs.runAs].orEmpty(),
                status,
                row[Runs.dryRun],
                plan,
                previewActions(runId),
                plan.statuses
                    .filter { it.status != "staged" || it.error != null }
                    .associate {
                        it.seq to
                            org.tatrman.automation.writer.RowOutcome(
                                it.seq,
                                it.status,
                                it.error,
                                it.prior,
                                it.history,
                                it.historyAt?.let(Instant::parse),
                                it.request,
                            )
                    },
                runDeadline(runId, defaultBudget),
            )
        }
    }

    /** The durable in-flight marker, set before a request leaves: a retried close reads it as an unknown outcome. */
    fun beginApply(
        batch: UUID,
        seqs: List<Int>,
        /** review-258 B3: the door request's id, kept with each row ([ApplyRow.REQUEST]); null = not recorded. */
        request: String? = null,
    ) {
        outermost("beginApply")
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val staged = (BatchRows.batch eq batch) and (BatchRows.seq inList seqs) and (BatchRows.status eq "staged")
            if (request == null) {
                BatchRows.update({ staged }) { it[error] = org.tatrman.automation.writer.RowOutcome.IN_FLIGHT }
                return@transaction
            }
            for (r in BatchRows.selectAll().where { staged }.toList()) {
                BatchRows.update({ (BatchRows.batch eq batch) and (BatchRows.seq eq r[BatchRows.seq]) }) {
                    it[error] = org.tatrman.automation.writer.RowOutcome.IN_FLIGHT
                    it[row] =
                        JsonObject(
                            (r[BatchRows.row] as? JsonObject).orEmpty() +
                                (org.tatrman.automation.apply.ApplyRow.REQUEST to JsonPrimitive(request)),
                        )
                }
            }
        }
    }

    /** Undo only the in-flight marks of a request that never left because its journal write used up the budget. */
    fun releaseApply(
        batch: UUID,
        seqs: List<Int>,
    ) {
        outermost("releaseApply")
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            BatchRows.update({
                (BatchRows.batch eq batch) and (BatchRows.seq inList seqs) and (BatchRows.status eq "staged") and
                    (BatchRows.error eq org.tatrman.automation.writer.RowOutcome.IN_FLIGHT)
            }) { it[error] = null }
        }
    }

    /**
     * Per-row outcomes as the door answered them; an `update`/`transition` keeps its `prior`, and a transition its
     * history sub-outcome (as `history_outcome`: the payload's own `history` is the §4 flag), in `batch_rows.row`.
     */
    fun recordApply(
        batch: UUID,
        outcomes: List<org.tatrman.automation.writer.RowOutcome>,
    ) {
        outermost("recordApply")
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            for (outcome in outcomes) {
                val stored =
                    BatchRows
                        .select(BatchRows.row)
                        .where { (BatchRows.batch eq batch) and (BatchRows.seq eq outcome.seq) }
                        .single()[BatchRows.row]
                BatchRows.update({ (BatchRows.batch eq batch) and (BatchRows.seq eq outcome.seq) }) {
                    it[status] = outcome.status
                    it[error] = outcome.error
                    if (stored is JsonObject && (outcome.prior != null || outcome.history != null)) {
                        val evidence =
                            listOfNotNull(
                                outcome.prior?.let { p -> "prior" to p },
                                outcome.history?.let { h -> HISTORY_OUTCOME to JsonPrimitive(h) },
                                outcome.committedAt?.let { a -> HISTORY_AT to JsonPrimitive(a.toString()) },
                            )
                        it[row] = JsonObject(stored + evidence)
                    }
                }
            }
        }
    }

    fun readRunBatch(
        runId: UUID,
        subject: String,
    ): RunBatchPlan {
        require(subject.isNotBlank())
        outermost("readRunBatch")
        return transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val row =
                Runs
                    .select(Runs.batchId)
                    .where { (Runs.id eq runId) and (Runs.callerSubject eq subject) }
                    .firstOrNull() ?: throw NoSuchRun(runId)
            batchPlan(runId, row[Runs.batchId] ?: throw NoRunBatch(runId))
        }
    }

    /**
     * Appends one entry to a section of the run's protocol (D-110: INSERT-only) and answers its `seq`. The run
     * row is locked first, so two appends to one run serialize and number their entries without a gap or a
     * clash. A sealed protocol refuses every append.
     *
     * READ COMMITTED, not the pool's REPEATABLE READ: under REPEATABLE READ the snapshot is taken by the first
     * statement — the lock itself — so an append that waited for the lock would still read the seq the earlier
     * append had replaced, and clash with it. Under READ COMMITTED each statement after the lock sees the
     * earlier append. The isolation is only set on a transaction of its own, so the append never runs inside
     * another.
     */
    fun appendProtocol(
        runId: UUID,
        section: ProtocolSection,
        payload: JsonElement,
    ): Int {
        outermost("appendProtocol")
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            if (isSealed(runId)) throw ProtocolSealed(runId)
            val seq = nextSeq(runId, section)
            RunProtocol.insert {
                it[run] = runId
                it[RunProtocol.section] = section.wire
                it[RunProtocol.seq] = seq
                it[RunProtocol.payload] = payload
                it[at] = clock.instant().utc()
            }
            seq
        }
    }

    /** Seals the protocol at close: a `{"sealed": true}` entry in `automation`, after which nothing appends. */
    fun sealProtocol(runId: UUID): Int = appendProtocol(runId, ProtocolSection.AUTOMATION, SEALED)

    /**
     * P1 close consumes a completed dry-run preview. Owner lookup, final write evidence, closure/seal and
     * ended_at are one locked transaction. No executor/current bundle is needed, even for a restarted retry.
     * A pending evaluation must finish first; close never starts work or converts a wet/held batch to preview.
     */
    fun closePreview(
        runId: UUID,
        subject: String,
    ): CloseResult {
        require(subject.isNotBlank())
        outermost("closePreview")
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val row =
                Runs
                    .selectAll()
                    .where { (Runs.id eq runId) and (Runs.callerSubject eq subject) }
                    .forUpdate()
                    .firstOrNull() ?: throw NoSuchRun(runId)
            val from = RunStatus.of(row[Runs.status])
            // S2.3: a waiting run is a wet run whose close already committed its `auto` rows and held the rest.
            if (from == RunStatus.WAITING) throw WetRunClose(runId)
            if (from !in setOf(RunStatus.EVALUATED, RunStatus.CLOSED)) {
                throw IllegalRunTransition(runId, from, RunStatus.CLOSED)
            }
            if (!row[Runs.dryRun]) throw WetRunClose(runId)
            val batchId = row[Runs.batchId] ?: throw NoRunBatch(runId)
            val batch = Batches.selectAll().where { (Batches.id eq batchId) and (Batches.run eq runId) }.single()
            check(batch[Batches.state] == "preview") { "P1 close requires a preview batch" }
            val result =
                CloseResult(
                    status = RunStatus.CLOSED.wire,
                    batch = RunBatch(batchId.toString(), batch[Batches.state], batch[Batches.rows]),
                    protocolRef = row[Runs.protocolRef] ?: "/automation/runs/$runId/protocol",
                )
            if (from == RunStatus.CLOSED) {
                check(isSealed(runId) && row[Runs.endedAt] != null) { "closed run is missing its seal/end time" }
                return@transaction result
            }
            if (isSealed(runId)) throw ProtocolSealed(runId)
            ensureStagingAvailable(runId)
            ensureRunBudget(runId, defaultBudget, clock)
            val now = clock.instant().utc()
            // The preview summary at evaluate is retained; this final entry records that close applied zero rows.
            val writes =
                kotlinx.serialization.json.buildJsonObject {
                    put("event", JsonPrimitive("closed"))
                    put("batch", JsonPrimitive(batchId.toString()))
                    put("state", JsonPrimitive("preview"))
                    put("rows", JsonPrimitive(batch[Batches.rows]))
                    put("applied", JsonPrimitive(0))
                }
            RunProtocol.insert {
                it[run] = runId
                it[section] = ProtocolSection.WRITES.wire
                it[seq] = nextSeq(runId, ProtocolSection.WRITES)
                it[payload] = writes
                it[at] = now
            }
            seal(runId, batchId, dryRun = true, now)
            ensureRunBudget(runId, defaultBudget, clock)
            Runs.update({ Runs.id eq runId }) {
                it[status] = RunStatus.CLOSED.wire
                it[endedAt] = now
            }
            result
        }
    }

    /**
     * S2.2 close of an `auto` run, after its rows were applied: the batch becomes `committed` (by the run's `run_as`),
     * the final `writes` entry counts each entity's applied/failed/skipped/unknown rows and the `stale-state` ones;
     * both `writes.history` and `writes.entities[entity].history` count final `{applied, failed, unknown}` history
     * sub-outcomes separately from the row counts (D-180, contracts §5). Failed/unknown history remains final on retry.
     * The protocol is sealed with CLOSED in one locked transaction. A row still pristine `staged`, or a transition still
     * `history: pending`, refuses: the apply did not finish. Like the preview close, it is fenced by the run's original
     * deadline before and after the evidence is written (`close-deadline-exceeded`; the batch stays `preview`). A committed batch with failures is still `committed` (the verdict stands, D-67).
     */
    fun closeCommitted(
        runId: UUID,
        subject: String,
    ): CloseResult {
        require(subject.isNotBlank())
        outermost("closeCommitted")
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val row =
                Runs
                    .selectAll()
                    .where { (Runs.id eq runId) and (Runs.callerSubject eq subject) }
                    .forUpdate()
                    .firstOrNull() ?: throw NoSuchRun(runId)
            val from = RunStatus.of(row[Runs.status])
            if (from !in setOf(RunStatus.EVALUATED, RunStatus.CLOSED, RunStatus.WAITING)) {
                throw IllegalRunTransition(runId, from, RunStatus.CLOSED)
            }
            check(!row[Runs.dryRun]) { "a committed close requires a wet run" }
            val batchId = row[Runs.batchId] ?: throw NoRunBatch(runId)
            val batch = Batches.selectAll().where { (Batches.id eq batchId) and (Batches.run eq runId) }.single()
            val protocolRef = row[Runs.protocolRef] ?: "/automation/runs/$runId/protocol"
            if (from == RunStatus.CLOSED) {
                check(isSealed(runId) && row[Runs.endedAt] != null) { "closed run is missing its seal/end time" }
                check(batch[Batches.state] in setOf("committed", "reverted")) {
                    "a closed wet run has an uncommitted batch"
                }
                return@transaction CloseResult(
                    RunStatus.CLOSED.wire,
                    RunBatch(batchId.toString(), batch[Batches.state], batch[Batches.rows]),
                    protocolRef,
                )
            }
            if (from == RunStatus.WAITING) {
                // S2.3: the close already committed the auto rows and sealed; the held groups await their decisions.
                check(isSealed(runId) && batch[Batches.state] == "held") { "a waiting run has no held batch" }
                return@transaction CloseResult(
                    RunStatus.WAITING.wire,
                    RunBatch(batchId.toString(), "held", batch[Batches.rows]),
                    protocolRef,
                )
            }
            if (isSealed(runId)) throw ProtocolSealed(runId)
            check(batch[Batches.state] == "preview") { "the run's batch was already committed" }
            ensureRunBudget(runId, defaultBudget, clock, wetClose = true)
            val all = BatchRows.selectAll().where { BatchRows.batch eq batchId }.toList()
            // S2.3: the rows of an `approval: required` action wait in their groups; the close applied only the rest.
            val held = all.filter(::undecidedHeld)
            val rows = all - held.toSet()
            check(rows.none { it[BatchRows.status] == "staged" && it[BatchRows.error] == null }) {
                "the apply did not finish: a row was never sent"
            }
            check(
                rows.none {
                    (it[BatchRows.row] as? JsonObject)?.get(HISTORY_OUTCOME) ==
                        JsonPrimitive(org.tatrman.automation.writer.RowOutcome.HISTORY_PENDING)
                },
            ) { "the apply did not finish: a transition's history request is unanswered" }
            val now = clock.instant().utc()
            val waiting = held.isNotEmpty()
            val outcome = if (waiting) "held" else "committed"
            Batches.update({ Batches.id eq batchId }) {
                it[state] = outcome
                if (!waiting) {
                    it[committedBy] = row[Runs.runAs]
                    it[committedAt] = now
                }
            }

            fun bucket(r: org.jetbrains.exposed.v1.core.ResultRow) =
                if (r[BatchRows.status] == "staged") "unknown" else r[BatchRows.status]
            val counts = rows.groupingBy(::bucket).eachCount()

            fun historyCounts(list: List<org.jetbrains.exposed.v1.core.ResultRow>): JsonObject {
                val histories =
                    list
                        .mapNotNull {
                            ((it[BatchRows.row] as? JsonObject)?.get(HISTORY_OUTCOME) as? JsonPrimitive)?.content
                        }.groupingBy { it }
                        .eachCount()
                return JsonObject(
                    listOf("applied", "failed", "unknown").associateWith {
                        JsonPrimitive(
                            histories[it] ?: 0,
                        )
                    },
                )
            }
            val writes =
                kotlinx.serialization.json.buildJsonObject {
                    put("event", JsonPrimitive("closed"))
                    put("batch", JsonPrimitive(batchId.toString()))
                    put("state", JsonPrimitive(outcome))
                    put("rows", JsonPrimitive(all.size))
                    for (name in listOf("applied", "failed", "skipped", "unknown")) {
                        put(
                            name,
                            JsonPrimitive(
                                counts[name] ?: 0,
                            ),
                        )
                    }
                    put("staleState", JsonPrimitive(rows.count { it[BatchRows.error] == "stale-state" }))
                    put("history", historyCounts(rows))
                    if (waiting) {
                        // S2.3: held rows are counted apart, with their groups; each decision appends after the seal.
                        put("held", JsonPrimitive(held.size))
                        put("groups", JsonPrimitive(held.map { it[BatchRows.groupKey] }.distinct().size))
                    }
                    put(
                        "entities",
                        JsonObject(
                            rows.groupBy { it[BatchRows.entity] }.toSortedMap().mapValues { (_, list) ->
                                val c = list.groupingBy(::bucket).eachCount()
                                JsonObject(
                                    listOf("applied", "failed", "skipped", "unknown").associateWith {
                                        JsonPrimitive(
                                            c[it] ?: 0,
                                        )
                                    } + ("history" to historyCounts(list)),
                                )
                            },
                        ),
                    )
                }
            RunProtocol.insert {
                it[run] = runId
                it[section] = ProtocolSection.WRITES.wire
                it[seq] = nextSeq(runId, ProtocolSection.WRITES)
                it[payload] = writes
                it[at] = now
            }
            seal(runId, batchId, dryRun = false, now)
            ensureRunBudget(runId, defaultBudget, clock, wetClose = true)
            val status = if (waiting) RunStatus.WAITING else RunStatus.CLOSED
            Runs.update({ Runs.id eq runId }) {
                it[Runs.status] = status.wire
                // D-97: a waiting run is never abandoned and has not ended; the last group's decision ends it.
                if (!waiting) it[endedAt] = now
            }
            CloseResult(status.wire, RunBatch(batchId.toString(), outcome, all.size), protocolRef)
        }
    }

    /**
     * The closure entry and the seal. Then D-80: a program with `protocol: on_route` whose run fired no non-`none`
     * route keeps no protocol document — every entry but the seal is removed (the D-73 retention opt-in, in this
     * transaction). The `runs` row and the batch stay: the ledger still knows the run, the 400-day record is omitted.
     */
    private fun seal(
        runId: UUID,
        batchId: UUID,
        dryRun: Boolean,
        now: OffsetDateTime,
    ) {
        val closure =
            kotlinx.serialization.json.buildJsonObject {
                put("event", JsonPrimitive("closed"))
                put("status", JsonPrimitive("closed"))
                put("dryRun", JsonPrimitive(dryRun))
                put("batch", JsonPrimitive(batchId.toString()))
            }
        val sequence = nextSeq(runId, ProtocolSection.AUTOMATION)
        listOf(closure, SEALED).forEachIndexed { index, evidence ->
            RunProtocol.insert {
                it[run] = runId
                it[section] = ProtocolSection.AUTOMATION.wire
                it[seq] = sequence + index
                it[payload] = evidence
                it[at] = now
            }
        }
        if (onRouteSilent(runId)) {
            val exec = TransactionManager.current()
            exec.exec("SET LOCAL automation.retention = 'on'")
            exec.exec(
                "DELETE FROM automation.run_protocol WHERE run = '$runId'::uuid " +
                    "AND NOT (section = 'automation' AND seq = ${sequence + 1})",
            )
            exec.exec("SET LOCAL automation.retention = 'off'")
        }
    }

    private fun onRouteSilent(runId: UUID): Boolean {
        fun entry(
            section: ProtocolSection,
            seq: Int,
        ) = RunProtocol
            .select(RunProtocol.payload)
            .where { (RunProtocol.run eq runId) and (RunProtocol.section eq section.wire) and (RunProtocol.seq eq seq) }
            .firstOrNull()
            ?.get(RunProtocol.payload) as? JsonObject
        val policy = (entry(ProtocolSection.AUTOMATION, 1)?.get("protocol") as? JsonPrimitive)?.content
        if (policy != "on_route" && policy != "on-route") return false
        val routes =
            ((entry(ProtocolSection.DECISION, 1)?.get("verdict") as? JsonObject)?.get("routes") as? JsonObject)
                ?: return false
        return routes.none { (name, summary) ->
            name != "none" &&
                (((summary as? JsonObject)?.get("rowCount") as? JsonPrimitive)?.content?.toLongOrNull() ?: 1L) > 0
        }
    }

    /**
     * S2.3: a held row whose group has no recorded decision — `group_key` set and no `decided` evidence, whatever its
     * apply progress (review-258 B4): a commit interrupted after journaling its rows `applied` (or in flight) is still
     * undecided, so another group's decision never closes the run without it (D-97).
     */
    private fun undecidedHeld(r: org.jetbrains.exposed.v1.core.ResultRow): Boolean =
        r[BatchRows.groupKey] != null && (r[BatchRows.row] as? JsonObject)?.get(DECIDED) == null

    /**
     * S2.3: what a decision on one held group reads — the run (its pinned fps and names, for D-99), the batch, every
     * row of the group with its recorded outcome, and the action evidence. The run is not owner-filtered: the decider
     * is an operator (`role.ops`), not the run's caller. A group the batch does not hold is [NoHeldGroup].
     */
    fun heldGroup(
        runId: UUID,
        group: JsonObject,
    ): HeldGroup {
        outermost("heldGroup")
        return transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val run = Runs.selectAll().where { Runs.id eq runId }.firstOrNull() ?: throw NoSuchRun(runId)
            val batchId = run[Runs.batchId] ?: throw NoRunBatch(runId)
            val plan = batchPlan(runId, batchId)
            val seqs =
                plan.rows
                    .filter { it.group == group }
                    .map { it.seq }
                    .toSet()
            if (seqs.isEmpty()) throw NoHeldGroup(runId, group)
            val batch = Batches.selectAll().where { Batches.id eq batchId }.single()
            HeldGroup(
                runId,
                batchId,
                RunStatus.of(run[Runs.status]),
                batch[Batches.state],
                RunNames(
                    run[Runs.recipe],
                    run[Runs.program],
                    run[Runs.deployment],
                    run[Runs.bundle],
                    run[Runs.runAs],
                ),
                RunFingerprints(
                    run[Runs.fpText],
                    run[Runs.fpValues],
                    run[Runs.fpProgram],
                    run[Runs.fpDeployment],
                    run[Runs.modelFp],
                ),
                plan.rows.filter { it.seq in seqs },
                plan.statuses.filter { it.seq in seqs },
                previewActions(runId),
                run[Runs.budgetBatch]?.let(Duration::ofMillis) ?: run[Runs.budgetSync]?.let(Duration::ofMillis)
                    ?: defaultBudget,
            )
        }
    }

    /**
     * S2.3 (Amendment 2026-11-03): records the decision on one held group, after its rows were applied (`commit`) or
     * instead of applying them (`reject`: the rows become `skipped`, error `rejected`). Every row of the group gets its
     * `decided` evidence; a `writes` entry is appended after the seal with `after_seal: true` (the seal stands, D-110).
     * When no undecided held row remains, the batch is `committed` by [by] and the run `closed` (D-97: the last decision
     * ends a waiting run). A group already decided answers its recorded decision and appends nothing.
     */
    fun decideHeld(
        runId: UUID,
        group: JsonObject,
        decision: String,
        by: String,
    ): HeldDecision {
        require(decision == "commit" || decision == "reject")
        require(by.isNotBlank())
        outermost("decideHeld")
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            val run = Runs.selectAll().where { Runs.id eq runId }.single()
            val batchId = run[Runs.batchId] ?: throw NoRunBatch(runId)
            val rows =
                BatchRows
                    .selectAll()
                    .where { BatchRows.batch eq batchId }
                    .orderBy(BatchRows.seq to SortOrder.ASC)
                    .toList()
            val mine = rows.filter { it[BatchRows.groupKey] == group }
            if (mine.isEmpty()) throw NoHeldGroup(runId, group)
            val recorded = (mine.first()[BatchRows.row] as? JsonObject)?.get(DECIDED) as? JsonObject
            if (recorded != null) {
                return@transaction HeldDecision(
                    batchId.toString(),
                    group,
                    (recorded["decision"] as JsonPrimitive).content,
                    mine.count { it[BatchRows.status] == "applied" },
                    mine.count { it[BatchRows.status] == "skipped" },
                    RunStatus.of(run[Runs.status]).wire,
                )
            }
            val now = clock.instant().utc()
            val evidence =
                kotlinx.serialization.json.buildJsonObject {
                    put("decision", JsonPrimitive(decision))
                    put("by", JsonPrimitive(by))
                    put("at", JsonPrimitive(now.toInstant().toString()))
                }
            for (r in mine) {
                BatchRows.update({ (BatchRows.batch eq batchId) and (BatchRows.seq eq r[BatchRows.seq]) }) {
                    if (decision == "reject" && r[BatchRows.status] == "staged" && r[BatchRows.error] == null) {
                        it[status] = "skipped"
                        it[error] = "rejected"
                    }
                    it[row] = JsonObject((r[BatchRows.row] as? JsonObject).orEmpty() + (DECIDED to evidence))
                }
            }
            val after = BatchRows.selectAll().where { BatchRows.batch eq batchId }.toList()
            val decided = after.filter { it[BatchRows.groupKey] == group }
            val counts =
                decided
                    .groupingBy { if (it[BatchRows.status] == "staged") "unknown" else it[BatchRows.status] }
                    .eachCount()
            val last = after.none(::undecidedHeld)
            RunProtocol.insert {
                it[RunProtocol.run] = runId
                it[section] = ProtocolSection.WRITES.wire
                it[seq] = nextSeq(runId, ProtocolSection.WRITES)
                it[payload] =
                    kotlinx.serialization.json.buildJsonObject {
                        put("event", JsonPrimitive("held-$decision"))
                        put("batch", JsonPrimitive(batchId.toString()))
                        put("group", group)
                        put("by", JsonPrimitive(by))
                        put("rows", JsonPrimitive(decided.size))
                        for (name in listOf("applied", "failed", "skipped", "unknown")) {
                            put(name, JsonPrimitive(counts[name] ?: 0))
                        }
                        put("last", JsonPrimitive(last))
                        put("after_seal", JsonPrimitive(true))
                    }
                it[at] = now
            }
            if (last) {
                Batches.update({ Batches.id eq batchId }) {
                    it[state] = "committed"
                    it[committedBy] = by
                    it[committedAt] = now
                }
                Runs.update({ Runs.id eq runId }) {
                    it[status] = RunStatus.CLOSED.wire
                    it[endedAt] = now
                }
            }
            HeldDecision(
                batchId.toString(),
                group,
                decision,
                decided.count { it[BatchRows.status] == "applied" },
                decided.count { it[BatchRows.status] == "skipped" },
                if (last) RunStatus.CLOSED.wire else RunStatus.of(run[Runs.status]).wire,
            )
        }
    }

    /**
     * S2.3 `-rev`: what a revert reads — the run's batch (it must be `committed`: `409 batch-not-committed`; a batch
     * already `reverted` is `409 batch-reverted`), its rows with their recorded outcomes and `prior`, the action
     * evidence, and a revert batch an interrupted attempt already journaled (`rev_of` = this batch). Not owner-filtered:
     * the reverter is an operator.
     */
    fun revertState(runId: UUID): RevertState {
        outermost("revertState")
        return transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val run = Runs.selectAll().where { Runs.id eq runId }.firstOrNull() ?: throw NoSuchRun(runId)
            val batchId = run[Runs.batchId] ?: throw NoRunBatch(runId)
            when (Batches.selectAll().where { Batches.id eq batchId }.single()[Batches.state]) {
                "committed" -> Unit
                "reverted" -> throw AutomationException(
                    ErrorCode.BATCH_REVERTED,
                    "the run's batch was already reverted",
                )
                else -> throw AutomationException(
                    ErrorCode.BATCH_NOT_COMMITTED,
                    "only a committed batch can be reverted",
                )
            }
            val rev =
                Batches
                    .select(Batches.id)
                    .where { Batches.revOf eq batchId }
                    .firstOrNull()
                    ?.get(Batches.id)
            RevertState(
                runId,
                batchId,
                RunNames(run[Runs.recipe], run[Runs.program], run[Runs.deployment], run[Runs.bundle], run[Runs.runAs]),
                batchPlan(runId, batchId),
                previewActions(runId),
                rev?.let { batchPlan(runId, it) },
                run[Runs.budgetBatch]?.let(Duration::ofMillis) ?: run[Runs.budgetSync]?.let(Duration::ofMillis)
                    ?: defaultBudget,
            )
        }
    }

    /** Journals the revert batch (`preview`, `rev_of` = [original], rows `staged`) once; a retry answers the same one. */
    fun beginRevert(
        runId: UUID,
        original: UUID,
        rows: List<ApplyRow>,
    ): RunBatchPlan {
        outermost("beginRevert")
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            val existing =
                Batches
                    .select(Batches.id)
                    .where { Batches.revOf eq original }
                    .firstOrNull()
                    ?.get(Batches.id)
            if (existing != null) return@transaction batchPlan(runId, existing)
            val id = UUID.randomUUID()
            Batches.insert {
                it[Batches.id] = id
                it[run] = runId
                it[state] = "preview"
                it[Batches.rows] = rows.size
                it[revOf] = original
            }
            BatchRows.batchInsert(rows, shouldReturnGeneratedValues = false) { item ->
                this[BatchRows.batch] = id
                this[BatchRows.seq] = item.seq
                this[BatchRows.entity] = item.entity
                this[BatchRows.op] = item.op
                this[BatchRows.key] = item.key
                this[BatchRows.row] = item.storedRow()
                this[BatchRows.groupKey] = null
                this[BatchRows.status] = "staged"
            }
            batchPlan(runId, id)
        }
    }

    /**
     * Ends a revert: the revert batch is `committed` by [by], the original `reverted`, and a `writes` entry is appended
     * after the seal (`after_seal: true`; D-110). Answers the revert batch's row counts.
     */
    fun finishRevert(
        runId: UUID,
        original: UUID,
        rev: UUID,
        by: String,
    ): RunBatchPlan {
        outermost("finishRevert")
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            val now = clock.instant().utc()
            val rows = BatchRows.selectAll().where { BatchRows.batch eq rev }.toList()
            val counts =
                rows
                    .groupingBy {
                        if (it[BatchRows.status] ==
                            "staged"
                        ) {
                            "unknown"
                        } else {
                            it[BatchRows.status]
                        }
                    }.eachCount()
            Batches.update({ Batches.id eq rev }) {
                it[state] = "committed"
                it[committedBy] = by
                it[committedAt] = now
            }
            Batches.update({ Batches.id eq original }) { it[state] = "reverted" }
            RunProtocol.insert {
                it[RunProtocol.run] = runId
                it[section] = ProtocolSection.WRITES.wire
                it[seq] = nextSeq(runId, ProtocolSection.WRITES)
                it[payload] =
                    kotlinx.serialization.json.buildJsonObject {
                        put("event", JsonPrimitive("revert"))
                        put("batch", JsonPrimitive(rev.toString()))
                        put("rev_of", JsonPrimitive(original.toString()))
                        put("by", JsonPrimitive(by))
                        put("rows", JsonPrimitive(rows.size))
                        for (name in listOf("applied", "failed", "skipped", "unknown")) {
                            put(name, JsonPrimitive(counts[name] ?: 0))
                        }
                        put("after_seal", JsonPrimitive(isSealed(runId)))
                    }
                it[at] = now
            }
            batchPlan(runId, rev)
        }
    }

    /**
     * S2.3 `outcome` (Amendment 2026-11-03): appended to the `actions` section; no `deliveries` row. A sealed protocol
     * refuses (`ProtocolSealed`), as every other append does.
     */
    fun appendOutcome(
        runId: UUID,
        subject: String,
        payload: JsonObject,
    ): Int {
        require(subject.isNotBlank())
        transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            Runs.select(Runs.id).where { (Runs.id eq runId) and (Runs.callerSubject eq subject) }.firstOrNull()
                ?: throw NoSuchRun(runId)
        }
        return appendProtocol(runId, ProtocolSection.ACTIONS, payload)
    }

    /** Durable owner/seal fence before touching a local executor, including after resource release/restart. */
    fun checkRunExecution(
        runId: UUID,
        subject: String,
    ) {
        require(subject.isNotBlank())
        outermost("checkRunExecution")
        transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val row =
                Runs
                    .select(Runs.status)
                    .where { (Runs.id eq runId) and (Runs.callerSubject eq subject) }
                    .firstOrNull() ?: throw NoSuchRun(runId)
            if (row[Runs.status] == RunStatus.CLOSED.wire || isSealed(runId)) throw ProtocolSealed(runId)
            if (row[Runs.status] == RunStatus.ABANDONED.wire) {
                throw AutomationException(ErrorCode.RUN_ABANDONED, "the run was abandoned")
            }
            ensureStagingAvailable(runId)
        }
    }

    /**
     * S3.1: a query or typed-action run ends in one transaction — its protocol [entries], the seal, and `closed`
     * (or `failed` with [error], which frees an idempotency key like any failed run). Only an executing run may end.
     */
    fun finishAdHocRun(
        runId: UUID,
        entries: List<Pair<ProtocolSection, JsonElement>>,
        error: JsonElement? = null,
    ) {
        outermost("finishAdHocRun")
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            val from = RunStatus.of(Runs.select(Runs.status).where { Runs.id eq runId }.single()[Runs.status])
            val to = if (error == null) RunStatus.CLOSED else RunStatus.FAILED
            if (from !in setOf(RunStatus.OPEN, RunStatus.RUNNING)) throw IllegalRunTransition(runId, from, to)
            if (isSealed(runId)) throw ProtocolSealed(runId)
            val now = clock.instant().utc()
            (entries + (ProtocolSection.AUTOMATION to SEALED)).forEach { (section, payload) ->
                val seq = nextSeq(runId, section)
                RunProtocol.insert {
                    it[run] = runId
                    it[RunProtocol.section] = section.wire
                    it[RunProtocol.seq] = seq
                    it[RunProtocol.payload] = payload
                    it[at] = now
                }
            }
            Runs.update({ Runs.id eq runId }) {
                it[status] = to.wire
                it[endedAt] = now
                if (error != null) it[Runs.error] = error
            }
        }
    }

    /**
     * S3.1 T4 `GET /automation/runs`: [subject]'s runs, newest first (`started_at`, then id), after the [before]
     * cursor and strictly after [since]; [status] null = every status.
     */
    fun listRuns(
        subject: String,
        deployment: String? = null,
        status: RunStatus? = null,
        kind: String? = null,
        since: Instant? = null,
        before: Pair<Instant, UUID>? = null,
        limit: Int = 100,
    ): List<RunListing> {
        require(subject.isNotBlank())
        // The route's public cap plus its one look-ahead row (review-273 F1).
        require(limit in 1..MAX_RUNS_PAGE + 1)
        return transaction {
            Runs
                .selectAll()
                .where {
                    var cond: org.jetbrains.exposed.v1.core.Op<Boolean> = Runs.callerSubject eq subject
                    if (deployment != null) cond = cond and (Runs.deployment eq deployment)
                    if (status != null) cond = cond and (Runs.status eq status.wire)
                    if (kind != null) cond = cond and (Runs.kind eq kind)
                    if (since != null) cond = cond and (Runs.startedAt greater since.utc())
                    if (before != null) {
                        val (at, id) = before
                        cond = cond and
                            ((Runs.startedAt less at.utc()) or ((Runs.startedAt eq at.utc()) and (Runs.id less id)))
                    }
                    cond
                }.orderBy(Runs.startedAt to SortOrder.DESC, Runs.id to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    RunListing(
                        id = row[Runs.id],
                        kind = row[Runs.kind],
                        subject = row[Runs.recipe],
                        program = row[Runs.program],
                        deployment = row[Runs.deployment],
                        status = RunStatus.of(row[Runs.status]),
                        origin = row[Runs.origin],
                        dryRun = row[Runs.dryRun],
                        startedAt = row[Runs.startedAt].toInstant(),
                        endedAt = row[Runs.endedAt]?.toInstant(),
                        protocolRef = row[Runs.protocolRef] ?: "/automation/runs/${row[Runs.id]}/protocol",
                        error = (row[Runs.error] as? JsonObject)?.get("code")?.let { (it as? JsonPrimitive)?.content },
                    )
                }
        }
    }

    /** The readiness probe's question: does the ledger's database answer? */
    fun ping(): Boolean =
        runCatching {
            transaction {
                maxAttempts = 1
                exec("SELECT 1") { it.next() } == true
            }
        }.getOrDefault(false)

    /** The D-72 health count: runs the clock abandoned. */
    fun countAbandoned(): Long =
        transaction {
            Runs.selectAll().where { Runs.status eq RunStatus.ABANDONED.wire }.count()
        }

    /**
     * D-46's clock sweep, including runs without an idempotency key. Only OPEN/RUNNING/EVALUATED can expire;
     * WAITING belongs to its tasks (D-97). The strict boundary and [runBudget] (an `on_event` run by its sync
     * budget, any other by its batch budget, D-192) match claimRun.
     *
     * Locks and updates commit together. Concurrent pods skip each other's locked rows, and a close/waiting
     * transition holding the row wins before the next sweep. Counters run only after commit. The caller must
     * separately reap local abandoned sessions, including runs abandoned by another pod or by claimRun.
     */
    fun abandonExpiredRuns(limit: Int = 100): List<UUID> {
        require(limit in 1..1000) { "abandonment sweep limit must be in 1..1000" }
        outermost("abandonExpiredRuns")
        val expired =
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val now = clock.instant()
                // Numeric elapsed milliseconds avoid floating-point rounding at the strict grace boundary.
                val rows =
                    exec(
                        """
                        SELECT id, recipe FROM automation.runs
                        WHERE status IN ('open', 'running', 'evaluated')
                          AND EXTRACT(EPOCH FROM (?::timestamptz - started_at)) * 1000
                              > (CASE WHEN mode = ? THEN COALESCE(budget_sync, budget_batch, ?)
                                      ELSE COALESCE(budget_batch, budget_sync, ?) END)::numeric + ?
                        ORDER BY started_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                        """.trimIndent(),
                        listOf(
                            TextColumnType() to now.utc().toString(),
                            TextColumnType() to ON_EVENT_MODE,
                            LongColumnType() to defaultBudget.toMillis(),
                            LongColumnType() to defaultBudget.toMillis(),
                            LongColumnType() to IdempotencyPolicy.GRACE.toMillis(),
                            IntegerColumnType() to limit,
                        ),
                        StatementType.SELECT,
                    ) { rs ->
                        buildList<Pair<UUID, String>> {
                            while (rs.next()) add(rs.getObject("id", UUID::class.java) to rs.getString("recipe"))
                        }
                    } ?: error("abandonment sweep answered no result")
                if (rows.isNotEmpty()) {
                    Runs.update({ Runs.id inList rows.map { it.first } }) {
                        it[status] = RunStatus.ABANDONED.wire
                        it[endedAt] = now.utc()
                        it[error] = JsonObject(mapOf("code" to JsonPrimitive("run-abandoned")))
                    }
                }
                rows
            }
        expired.forEach { (_, recipe) -> abandoned(recipe) }
        return expired.map { it.first }
    }

    /**
     * Owner-filtered repeatable-read snapshot: status and its immutable decision cannot straddle a commit.
     * Terminal/expired runs remain observable, without a current bundle, local promise or execution budget.
     * Task/delivery/action projections arrive with their lifecycle implementations.
     */
    fun readRunProgress(
        runId: UUID,
        subject: String,
    ): RunProgress {
        require(subject.isNotBlank())
        outermost("readRunProgress")
        return transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val row =
                Runs
                    .selectAll()
                    .where { (Runs.id eq runId) and (Runs.callerSubject eq subject) }
                    .firstOrNull() ?: throw NoSuchRun(runId)
            val decisions =
                RunProtocol
                    .select(RunProtocol.payload)
                    .where { (RunProtocol.run eq runId) and (RunProtocol.section eq ProtocolSection.DECISION.wire) }
                    .toList()
            check(decisions.size <= 1) { "a run must have at most one decision" }
            val result =
                decisions.singleOrNull()?.let {
                    Json.decodeFromJsonElement<EvaluationResult>(it[RunProtocol.payload])
                }
            val batch =
                row[Runs.batchId]?.let { id ->
                    val b = Batches.selectAll().where { (Batches.id eq id) and (Batches.run eq runId) }.single()
                    RunBatch(id.toString(), b[Batches.state], b[Batches.rows])
                }
            RunProgress(
                runId = runId.toString(),
                status = row[Runs.status],
                verdict = result?.verdict,
                guards = result?.guards.orEmpty(),
                mode = result?.mode,
                protocolRef = row[Runs.protocolRef] ?: "/automation/runs/$runId/protocol",
                recipe = row[Runs.recipe],
                program = row[Runs.program],
                deployment = row[Runs.deployment],
                runAs = row[Runs.runAs],
                author = row[Runs.author],
                asOf = row[Runs.asOf]?.toInstant()?.toString(),
                bundle = row[Runs.bundle],
                fingerprints =
                    RunFingerprints(
                        row[Runs.fpText],
                        row[Runs.fpValues],
                        row[Runs.fpProgram],
                        row[Runs.fpDeployment],
                        row[Runs.modelFp],
                    ),
                dryRun = row[Runs.dryRun],
                replayOf = row[Runs.replayOf]?.toString(),
                startedAt = row[Runs.startedAt].toInstant().toString(),
                endedAt = row[Runs.endedAt]?.toInstant()?.toString(),
                error = row[Runs.error],
                batch = batch,
                actions = previewActions(runId),
            )
        }
    }

    fun findRun(id: UUID): RunRecord? =
        transaction {
            Runs.selectAll().where { Runs.id eq id }.firstOrNull()?.let { row ->
                RunRecord(
                    id,
                    RunOpen(
                        recipe = row[Runs.recipe],
                        program = row[Runs.program],
                        deployment = row[Runs.deployment],
                        bundle = row[Runs.bundle],
                        fpText = row[Runs.fpText],
                        fpValues = row[Runs.fpValues],
                        fpProgram = row[Runs.fpProgram],
                        fpDeployment = row[Runs.fpDeployment],
                        modelFp = row[Runs.modelFp],
                        port = row[Runs.port],
                        asOf = row[Runs.asOf]?.toInstant(),
                        author = row[Runs.author],
                        runAs = row[Runs.runAs],
                        caller = row[Runs.caller],
                        callerSubject = row[Runs.callerSubject],
                        origin = row[Runs.origin],
                        dryRun = row[Runs.dryRun],
                        governed = row[Runs.governance] == "governed",
                        idempotencyKey = row[Runs.idempotencyKey],
                        replayOf = row[Runs.replayOf],
                        budgetSync = row[Runs.budgetSync]?.let(Duration::ofMillis),
                        budgetBatch = row[Runs.budgetBatch]?.let(Duration::ofMillis),
                        mode = row[Runs.mode],
                        kind = row[Runs.kind],
                    ),
                    RunStatus.of(row[Runs.status]),
                    row[Runs.protocolRef],
                )
            }
        }

    /** Records a staged island output; a late worker cannot publish after abandonment/close/failure/sealing. */
    fun putOutput(output: RunOutput) = recordOutput(output, null)

    fun putOutput(
        output: RunOutput,
        execution: IslandExecution,
    ) {
        require(execution.kind == "output" && execution.name == output.island && execution.port == output.port)
        require(execution.rows == output.rows && execution.bytes == output.bytes)
        recordOutput(output, execution)
    }

    private fun recordOutput(
        output: RunOutput,
        execution: IslandExecution?,
    ) {
        outermost("putOutput")
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(output.run)
            val from = RunStatus.of(Runs.select(Runs.status).where { Runs.id eq output.run }.single()[Runs.status])
            if (from !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) {
                throw IllegalRunTransition(output.run, from, RunStatus.RUNNING)
            }
            if (isSealed(output.run)) throw ProtocolSealed(output.run)
            ensureStagingAvailable(output.run)
            ensureRunBudget(output.run, defaultBudget, clock)
            val existing =
                RunOutputs
                    .selectAll()
                    .where {
                        (RunOutputs.run eq output.run) and (RunOutputs.island eq output.island) and
                            (RunOutputs.port eq output.port)
                    }.firstOrNull()
            if (execution != null) appendExecution(output.run, execution)
            if (existing != null) {
                check(
                    existing[RunOutputs.sha256] == output.sha256 &&
                        existing[RunOutputs.rows] == output.rows &&
                        existing[RunOutputs.bytes] == output.bytes &&
                        existing[RunOutputs.path] == output.path &&
                        existing[RunOutputs.releasedAt] == null,
                ) { "a staged output cannot change within one pinned run" }
            } else {
                RunOutputs.insert {
                    it[run] = output.run
                    it[island] = output.island
                    it[port] = output.port
                    it[rows] = output.rows
                    it[bytes] = output.bytes
                    it[path] = output.path
                    it[sha256] = output.sha256
                }
            }
        }
    }

    /** Successful shared-part evidence: immutable by (kind, name, port), fenced by status, seal and budget. */
    fun recordExecution(
        runId: UUID,
        execution: IslandExecution,
    ) {
        require(execution.kind == "part") { "output evidence must commit with its output row" }
        outermost("recordExecution")
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            val from = RunStatus.of(Runs.select(Runs.status).where { Runs.id eq runId }.single()[Runs.status])
            if (from !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) {
                throw IllegalRunTransition(runId, from, RunStatus.RUNNING)
            }
            if (isSealed(runId)) throw ProtocolSealed(runId)
            ensureStagingAvailable(runId)
            ensureRunBudget(runId, defaultBudget, clock)
            appendExecution(runId, execution)
        }
    }

    /**
     * One consistent snapshot, including authorization. Unknown/other/legacy subjects answer the same failure;
     * neither the current bundle nor a live executor is needed. Only the configured verifier supplies subject.
     */
    fun readProtocol(
        runId: UUID,
        subject: String,
    ): ProtocolDocument {
        require(subject.isNotBlank())
        outermost("readProtocol")
        return transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            Runs
                .select(Runs.id)
                .where { (Runs.id eq runId) and (Runs.callerSubject eq subject) }
                .firstOrNull() ?: throw NoSuchRun(runId)
            val rows =
                RunProtocol
                    .selectAll()
                    .where { RunProtocol.run eq runId }
                    .orderBy(RunProtocol.seq to SortOrder.ASC)
                    .toList()
            ProtocolDocument(
                runId.toString(),
                ProtocolSection.entries.associate { section ->
                    section.wire to
                        rows.filter { it[RunProtocol.section] == section.wire }.map { row ->
                            ProtocolEntry(
                                row[RunProtocol.seq],
                                row[RunProtocol.at].toInstant().toString(),
                                row[RunProtocol.payload],
                            )
                        }
                },
            )
        }
    }

    /** Called only while holding the run row, in the step/output transaction. */
    private fun appendExecution(
        runId: UUID,
        execution: IslandExecution,
    ) {
        val payload = Json.encodeToJsonElement(execution)
        val entries =
            RunProtocol
                .selectAll()
                .where {
                    (RunProtocol.run eq runId) and (RunProtocol.section eq ProtocolSection.ISLANDS.wire)
                }.toList()
        val existing =
            entries.firstOrNull { row ->
                val value = row[RunProtocol.payload] as? JsonObject
                value?.get("kind") == JsonPrimitive(execution.kind) &&
                    value["name"] == JsonPrimitive(execution.name) &&
                    value["port"] == (execution.port?.let(::JsonPrimitive))
            }
        if (existing != null) {
            check(
                sameJson(existing[RunProtocol.payload], payload),
            ) { "a pinned run's execution evidence cannot change" }
            return
        }
        RunProtocol.insert {
            it[run] = runId
            it[section] = ProtocolSection.ISLANDS.wire
            it[seq] = nextSeq(runId, ProtocolSection.ISLANDS)
            it[RunProtocol.payload] = payload
            it[at] = clock.instant().utc()
        }
    }

    /** Opens a task; one open per (recipe, key, action) (D-98). */
    fun openTask(task: TaskOpen): UUID = TODO("S3.3 — ask/start/deliver tasks")

    /** Records a task's answer (D-99's fingerprint check is the caller's). */
    fun answerTask(
        taskId: UUID,
        answer: JsonElement,
        by: String,
    ): Unit = TODO("S3.3 — POST /tasks/{t}/answer")

    /** The tasks a person sees (`GET /tasks?for=me`, D-101). */
    fun listTasksFor(
        person: String,
        status: String?,
    ): List<UUID> = TODO("S3.3 — GET /tasks?for=me")

    private fun insertRun(
        open: RunOpen,
        now: Instant,
    ): UUID {
        val runId = UUID.randomUUID()
        Runs.insert {
            it[id] = runId
            it[recipe] = open.recipe
            it[program] = open.program
            it[deployment] = open.deployment
            it[bundle] = open.bundle
            it[fpText] = open.fpText
            it[fpValues] = open.fpValues
            it[fpProgram] = open.fpProgram
            it[fpDeployment] = open.fpDeployment
            it[modelFp] = open.modelFp
            it[port] = open.port
            it[asOf] = open.asOf?.utc()
            it[author] = open.author
            it[runAs] = open.runAs
            it[caller] = open.caller
            it[callerSubject] = open.callerSubject
            it[origin] = open.origin
            it[status] = RunStatus.OPEN.wire
            it[dryRun] = open.dryRun
            it[governance] = if (open.governed) "governed" else "ungoverned"
            it[idempotencyKey] = open.idempotencyKey
            it[replayOf] = open.replayOf
            it[startedAt] = now.utc()
            it[budgetSync] = open.budgetSync?.toMillis()
            it[budgetBatch] = open.budgetBatch?.toMillis()
            it[mode] = open.mode
            it[kind] = open.kind
        }
        open.openingProtocol?.let { payload ->
            RunProtocol.insert {
                it[run] = runId
                it[section] = ProtocolSection.AUTOMATION.wire
                it[seq] = 1
                it[RunProtocol.payload] = payload
                it[at] = now.utc()
            }
        }
        return runId
    }

    private fun outermost(operation: String) =
        check(TransactionManager.currentOrNull() == null) {
            "Ledger.$operation runs in a transaction of its own; called inside another, its retry and isolation " +
                "would not apply"
        }

    private fun lockRun(runId: UUID) {
        Runs
            .select(Runs.id)
            .where { Runs.id eq runId }
            .forUpdate()
            .firstOrNull() ?: throw NoSuchRun(runId)
    }

    private fun isSealed(runId: UUID): Boolean =
        RunProtocol
            .select(RunProtocol.payload)
            .where { (RunProtocol.run eq runId) and (RunProtocol.section eq ProtocolSection.AUTOMATION.wire) }
            .orderBy(RunProtocol.seq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { ((it[RunProtocol.payload] as? JsonObject)?.get("sealed") as? JsonPrimitive)?.booleanOrNull == true }
            ?: false

    private fun nextSeq(
        runId: UUID,
        section: ProtocolSection,
    ): Int =
        RunProtocol
            .select(RunProtocol.seq)
            .where { (RunProtocol.run eq runId) and (RunProtocol.section eq section.wire) }
            .orderBy(RunProtocol.seq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { it[RunProtocol.seq] + 1 }
            ?: 1

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    companion object {
        const val CLAIM_ATTEMPTS = 3

        /** `GET /automation/runs`' largest page (S3.1 T4); [listRuns] reads one more row to know a next page exists. */
        const val MAX_RUNS_PAGE = 500
        private val FREE_STATUSES = RunStatus.entries.filter { it.freesKey }.map { it.wire }
        private val SEALED = JsonObject(mapOf("sealed" to JsonPrimitive(true)))
    }
}
