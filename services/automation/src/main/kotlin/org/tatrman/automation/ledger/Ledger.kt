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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.automation.runner.EvaluationResult
import org.tatrman.automation.runner.RunProgress
import org.tatrman.automation.runner.RunFingerprints
import org.tatrman.automation.runner.RunBatch
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
    /** Written in the same transaction as a new claim; never appended again for a replay/live handle. */
    val openingProtocol: JsonElement? = null,
    /** Verified JWT subject; never infer this from the caller's display principal. Legacy rows stay null. */
    val callerSubject: String? = null,
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

class IllegalRunTransition(
    val runId: UUID,
    val from: RunStatus,
    val to: RunStatus,
) : RuntimeException("run `$runId` cannot move from ${from.wire} to ${to.wire}")

class ProtocolSealed(
    val runId: UUID,
) : RuntimeException("the protocol of run `$runId` is sealed (D-110)")

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
                            budget = budgetOf(it[Runs.budgetBatch], it[Runs.budgetSync]),
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
    ) {
        outermost("completeEvaluation")
        val payload = Json.encodeToJsonElement(result)
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            lockRun(runId)
            val from = RunStatus.of(Runs.select(Runs.status).where { Runs.id eq runId }.single()[Runs.status])
            if (from !in setOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) {
                throw IllegalRunTransition(runId, from, RunStatus.EVALUATED)
            }
            if (isSealed(runId)) throw ProtocolSealed(runId)
            val existing =
                RunProtocol
                    .selectAll()
                    .where {
                        (RunProtocol.run eq runId) and (RunProtocol.section eq ProtocolSection.DECISION.wire)
                    }.toList()
            if (existing.isNotEmpty()) {
                check(
                    existing.size == 1 &&
                        existing.single()[RunProtocol.payload] == payload &&
                        from == RunStatus.EVALUATED,
                ) {
                    "a pinned run's decision cannot change"
                }
            } else {
                ensureRunBudget(runId, defaultBudget, clock)
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
     * WAITING belongs to its tasks (D-97). The strict boundary and batch → sync → default budget match claimRun.
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
                              > COALESCE(budget_batch, budget_sync, ?)::numeric + ?
                        ORDER BY started_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                        """.trimIndent(),
                        listOf(
                            TextColumnType() to now.utc().toString(),
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
            check(existing[RunProtocol.payload] == payload) { "a pinned run's execution evidence cannot change" }
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

    private fun budgetOf(
        batchMs: Long?,
        syncMs: Long?,
    ): Duration = (batchMs ?: syncMs)?.let(Duration::ofMillis) ?: defaultBudget

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    companion object {
        const val CLAIM_ATTEMPTS = 3
        private val FREE_STATUSES = RunStatus.entries.filter { it.freesKey }.map { it.wire }
        private val SEALED = JsonObject(mapOf("sealed" to JsonPrimitive(true)))
    }
}
