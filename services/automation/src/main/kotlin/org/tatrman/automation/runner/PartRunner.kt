// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.tatrman.automation.bundle.LoadedRecipe
import org.tatrman.automation.apply.PreviewMetadata
import org.tatrman.automation.apply.PreviewPlan
import org.tatrman.automation.bundle.ManifestOutput
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.protocol.IslandExecution
import org.tatrman.automation.protocol.ProtocolRecorder
import org.tatrman.automation.staging.StagedOutput
import org.tatrman.automation.staging.Staging
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The immutable execution inputs pinned at open. The API/ledger open policy owns their validation. */
data class RunContext(
    val id: UUID,
    val recipe: LoadedRecipe,
    val world: String,
    val modelFp: String,
    val identity: RunIdentity,
    val bindings: Map<String, BoundValue>,
    /** null = all candidates; an empty list = no candidates. Composite keys are maps of column names. */
    val keys: List<Map<String, Any?>>? = null,
    val readCeiling: Int = 100_000,
    val timeout: Duration = Duration.ofMinutes(30),
    val allowRestrictedView: Boolean = false,
    val detailLimit: Int = 200,
    val detailLimits: Map<String, Int> = emptyMap(),
    val syncBudget: Duration = Duration.ofSeconds(60),
    /** Production Open replaces this with the committed ledger claim timestamp. */
    val startedAt: java.time.Instant = java.time.Instant.now(),
    val previewMetadata: PreviewMetadata? = null,
    /** Compiler-resolved logical storage for an auxiliary obligation lookup, pinned at Open. */
    val obligationStorage: String? = null,
) {
    init {
        require(readCeiling > 0 && detailLimit > 0 && !timeout.isNegative && !timeout.isZero)
        require(!syncBudget.isNegative && syncBudget.toMillis() > 0) { "invalid sync budget" }
        require(detailLimits.values.all { it > 0 })
        require(
            detailLimits.keys.all { name ->
                recipe.recipe.roles
                    ?.routes
                    ?.any { it.name == name } == true
            },
        )
        val names =
            recipe.recipe.key
                .map { it.substringAfterLast('.') }
                .toSet()
        require(
            keys == null ||
                names.isNotEmpty() &&
                keys.all {
                    it.keys == names &&
                        it.values.none { v ->
                            v == null
                        }
                },
        ) {
            "run keys must carry every recipe key column"
        }
    }
}

/**
 * Run-local executor (D-135/D-136). Distinct parts and outputs are promises: overlapping callers share one read,
 * including its failure. Waves finish in order, with at most four concurrent door reads. DuckDB sees only these
 * governed parts; an output's combine and its compatibility file are never sent to Query.
 * The owning run closes this executor after all work has finished (close/retention policy is S1.3).
 */
class PartRunner(
    val context: RunContext,
    private val door: Door,
    private val staging: Staging,
    private val scope: CoroutineScope,
    /** Must durably record the decision and EVALUATED status together before evaluate returns. */
    private val recordEvaluation: (
        UUID,
        EvaluationResult,
    ) -> Unit = { _, _ -> error("evaluation journal unavailable") },
    /** Runtime supplies the durable worker dispatcher; all evaluation entry paths share this hook. */
    private val dispatch: (suspend (UUID, CoroutineScope, suspend () -> EvaluationResult) -> EvaluationResult)? = null,
    /** Runtime supplies the durable successful-part journal, before its rows enter a local combine. */
    private val recordRead: (UUID, IslandExecution) -> Unit = { _, _ -> },
    private val preview: (suspend (PartRunner) -> PreviewPlan)? = null,
    private val recordPreview: ((UUID, EvaluationResult, PreviewPlan) -> Unit)? = null,
) : AutoCloseable {
    private val islands =
        context.recipe.manifest.islands
            .associateBy { it.name }
    private val parts =
        context.recipe.recipe.parts
            .associateBy { it.name }
    private val lock = Mutex()
    private val slots = Semaphore(4)
    private val partReads = mutableMapOf<String, Deferred<Unit>>()
    private val outputs = mutableMapOf<String, Deferred<StagedOutput>>()
    private var evaluation: Deferred<EvaluationResult>? = null
    private var obligationRead: Pair<Set<String>, Deferred<StagedOutput>>? = null

    @Volatile internal var evaluationRecorded = false
        private set
    private val combine = lazy { DuckDbCombiner() }
    private val checks = Guards(context)
    val guards: List<GuardResult> get() = checks.results
    private val observedWarnings = ConcurrentHashMap<String, Set<String>>()
    val warningsByRead: Map<String, Set<String>> get() = observedWarnings.toMap()
    val warnings: Set<String> get() = observedWarnings.values.flatten().toSet()

    init {
        require((preview == null) == (recordPreview == null)) { "preview planning and journalling must be paired" }
        require(context.bindings.keys.none { it.startsWith("_au_port_") }) { "reserved port binding prefix in recipe" }
        require(islands.size == context.recipe.manifest.islands.size) { "duplicate island" }
        require(parts.size == context.recipe.recipe.parts.size) { "duplicate storage part" }
        val scheduled =
            context.recipe.manifest.waves
                .flatten()
        require(scheduled.toSet() == islands.keys && scheduled.size == islands.size) {
            "waves must name each island exactly once"
        }
    }

    /** Runs all preceding waves, then the named island. Missing inputs never silently become empty tables. */
    suspend fun island(name: String): List<StagedOutput> {
        checks.ensureAllowed()
        require(name in islands) { "unknown island $name" }
        for (wave in context.recipe.manifest.waves) {
            if (name in wave) return outputIsland(name)
            kotlinx.coroutines.coroutineScope { wave.map { async { outputIsland(it) } }.awaitAll() }
        }
        error("island is not scheduled")
    }

    suspend fun all(): List<StagedOutput> {
        checks.ensureAllowed()
        val result = mutableListOf<StagedOutput>()
        for (wave in context.recipe.manifest.waves) {
            result += kotlinx.coroutines.coroutineScope { wave.map { async { outputIsland(it) } }.awaitAll() }.flatten()
        }
        checks.ensureAllowed()
        return result
    }

    /** One shared evaluation per pinned run, including journal failures; disconnecting a waiter cannot restart it. */
    suspend fun evaluate(): EvaluationResult {
        checks.ensureAllowed()
        val work =
            lock.withLock {
                evaluation ?: scope
                    .async {
                        val execute: suspend () -> EvaluationResult = {
                            val decision = summarizeEvaluation(this@PartRunner)
                            val plan = preview?.invoke(this@PartRunner)
                            val result = decision.copy(guards = guards)
                            checks.ensureAllowed()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                if (plan == null) {
                                    recordEvaluation(context.id, result)
                                } else {
                                    requireNotNull(recordPreview).invoke(context.id, result, plan)
                                }
                                evaluationRecorded = true
                            }
                            checks.ensureAllowed()
                            result
                        }
                        dispatch?.invoke(context.id, scope, execute) ?: execute()
                    }.also { evaluation = it }
            }
        return work.await().also { checks.ensureAllowed() }
    }

    /** Observes completed work only: a status poll cannot execute or journal a decision. */
    internal suspend fun completedEvaluation(): EvaluationResult? {
        val work = lock.withLock { evaluation?.takeIf { it.isCompleted } } ?: return null
        return work.await().also { checks.ensureAllowed() }
    }

    internal suspend fun readStaged(output: StagedOutput): Table = stagedData(output).table

    /** Preview observes already published promises only; it cannot start a missing island or Query read. */
    internal suspend fun completedIsland(name: String): List<StagedOutput> {
        checks.ensureAllowed()
        val ready =
            lock.withLock {
                islands.getValue(name).outputs.map { output ->
                    checkNotNull(outputs["$name.${output.port}"]?.takeIf { it.isCompleted }) {
                        "preview requires completed staged outputs"
                    }
                }
            }
        return ready.awaitAll().also { checks.ensureAllowed() }
    }

    internal suspend fun stagedData(output: StagedOutput): org.tatrman.automation.staging.StagedData {
        checks.ensureAllowed()
        require(output.output.run == context.id)
        return staging.readData(output).also { checks.ensureAllowed() }
    }

    /** Explicit auxiliary read: the manifest must already authorize the stdlib entity. No fallback door route. */
    internal suspend fun obligationSnapshot(entities: Set<String>): Table {
        val island = "_au_obligations"
        val entity = "er.entity.obligation"
        checks.ensureAllowed()
        if (entity !in context.recipe.recipe.objectsRead) {
            checks.doorRefused(
                island,
                org.tatrman.automation.api.AutomationException(
                    org.tatrman.automation.api.ErrorCode.OUT_OF_SCOPE_READ,
                    "obligation lookup requires declared ER scope",
                ),
            )
        }
        if (entities.isEmpty() || island in islands) {
            throw org.tatrman.automation.api.AutomationException(
                org.tatrman.automation.api.ErrorCode.PREVIEW_UNSUPPORTED,
                "invalid obligation lookup plan",
            )
        }
        val storage =
            context.obligationStorage ?: throw org.tatrman.automation.api.AutomationException(
                org.tatrman.automation.api.ErrorCode.PREVIEW_UNSUPPORTED,
                "obligation storage was not pinned",
            )
        val work =
            lock.withLock {
                obligationRead?.let { (pinned, work) ->
                    require(pinned == entities) { "obligation lookup scope changed" }
                    work
                } ?: scope
                    .async {
                        val started = System.nanoTime()
                        val parameters =
                            entities
                                .sorted()
                                .mapIndexed { i, name ->
                                    "_au_obligation_entity_$i" to
                                        BoundValue("text", name)
                                }.toMap()
                        val columns =
                            listOf("id", "entity", "key", "kind", "state").map {
                                org.tatrman.automation.bundle
                                    .ManifestColumn(it, "text")
                            }
                        val sql =
                            "SELECT ob.id, ob.entity, ob.key, ob.kind, ob.state " +
                                "FROM er.entity.obligation ob WHERE ob.entity IN (" +
                                parameters.keys.joinToString(", ") { ":$it" } +
                                ")"
                        val result =
                            read(
                                island,
                                storage,
                                "runtime:obligation-lookup/v1",
                                emptyList(),
                                columns,
                                CandidateScope.Statement(sql, parameters),
                            )
                        staging.put(
                            context.id,
                            island,
                            "rows",
                            result.table,
                            IslandExecution(
                                "output",
                                island,
                                "runtime:obligation-lookup/v1",
                                "query",
                                ProtocolRecorder.parameters(parameters),
                                result.table.rows.size
                                    .toLong(),
                                elapsedMs(started),
                                result.warnings.sorted(),
                                port = "rows",
                                storage = storage,
                            ),
                        )
                    }.also { obligationRead = entities.toSet() to it }
            }
        return readStaged(work.await()).also { checks.ensureAllowed() }
    }

    private suspend fun outputIsland(name: String): List<StagedOutput> =
        kotlinx.coroutines.coroutineScope {
            islands
                .getValue(name)
                .outputs
                .map { output -> async { output(name, output) } }
                .awaitAll()
        }

    private suspend fun output(
        island: String,
        output: ManifestOutput,
    ): StagedOutput {
        val key = "$island.${output.port}"
        val work =
            lock.withLock {
                outputs.getOrPut(key) {
                    scope.async {
                        val started = System.nanoTime()
                        var readWarnings: Set<String> = emptySet()
                        val table =
                            if (output.combine != null) {
                                val plan = output.combine
                                require(
                                    plan.engine == "duckdb" && plan.dialect == "duckdb",
                                ) { "unsupported combine engine" }
                                kotlinx.coroutines.coroutineScope { plan.parts.map { async { part(it) } }.awaitAll() }
                                readWarnings = plan.parts.flatMap { observedWarnings["parts/$it"].orEmpty() }.toSet()
                                slots.withPermit {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val scoped = candidateStatement(island, output, statement(plan.file))
                                        combine.value.query(scoped.sql, bindings(plan.params) + scoped.bindings)
                                    }
                                }
                            } else {
                                require(
                                    islands.getValue(island).schema == "er",
                                ) { "direct outputs must be SQL over E-R" }
                                read(
                                    island,
                                    requireNotNull(output.storage) { "direct output has no storage" },
                                    output.file,
                                    output.params,
                                    output.columns,
                                    candidateStatement(island, output, statement(output.file)),
                                ).also { readWarnings = it.warnings }.table
                            }
                        // A combine may expand several individually short sources to the ceiling.
                        checks.ceiling(island, table)
                        // AU-C-3: filter EVERY keyed output, including combines and detail ports, before staging.
                        val scoped = if (output.keyed) filter(table) else table
                        if (island ==
                            context.recipe.recipe.roles
                                ?.candidates
                        ) {
                            checks.candidates(island, scoped)
                        }
                        checks.ensureAllowed()
                        val plan = output.combine
                        val file = plan?.file ?: output.file
                        val declared = plan?.params ?: output.params
                        val params = bindings(declared) + candidateStatement(island, output, statement(file)).bindings
                        val published = snapshot(island, output, scoped)
                        staging.put(
                            context.id,
                            island,
                            output.port,
                            published,
                            IslandExecution(
                                "output",
                                island,
                                file,
                                if (plan != null) "duckdb" else "query",
                                ProtocolRecorder.parameters(params),
                                published.rows.size.toLong(),
                                elapsedMs(started),
                                readWarnings.sorted(),
                                port = output.port,
                                storage = output.storage.takeIf { plan == null },
                            ),
                        )
                    }
                }
            }
        return work.await().also { checks.ensureAllowed() }
    }

    private fun elapsedMs(started: Long): Long = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)

    private fun snapshot(
        island: String,
        output: ManifestOutput,
        table: Table,
    ): Table {
        val roles = context.recipe.recipe.roles ?: return table
        if (island != roles.decision) return table
        val route =
            roles.routes.singleOrNull { route ->
                route.detail.any { output.port == "${route.name}.$it" }
            } ?: return table
        require(output.keyed) { "detail output must be keyed" }
        val detailContext = route.detail.single { output.port == "${route.name}.$it" }
        for ((column, expected) in mapOf("route" to route.name, "context" to detailContext)) {
            val index = table.columns.indexOfFirst { it.name == column }
            require(index >= 0 && table.rows.all { it[index] == expected }) {
                "detail stamp differs from its port"
            }
        }
        val keys =
            context.recipe.recipe.key
                .map { it.substringAfterLast('.') }
        return boundedDetails(table, keys, context.detailLimits[route.name] ?: context.detailLimit)
    }

    private suspend fun part(name: String) {
        val work =
            lock.withLock {
                partReads.getOrPut(name) {
                    scope.async {
                        val part = parts.getValue(name)
                        require(part.schema == "er") { "parts must be SQL over E-R" }
                        val started = System.nanoTime()
                        val result = read("parts/$name", part.storage, part.file, part.params, part.columns)
                        val table = result.table
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            recordRead(
                                context.id,
                                IslandExecution(
                                    "part",
                                    name,
                                    part.file,
                                    "query",
                                    ProtocolRecorder.parameters(bindings(part.params)),
                                    table.rows.size.toLong(),
                                    elapsedMs(started),
                                    result.warnings.sorted(),
                                    storage = part.storage,
                                ),
                            )
                        }
                        // Do not filter storage parts blindly: even keyed parts can feed set-level calculations.
                        kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO,
                        ) { combine.value.load(name, table) }
                    }
                }
            }
        work.await()
    }

    private suspend fun read(
        island: String,
        storage: String,
        file: String,
        params: List<String>,
        columns: List<org.tatrman.automation.bundle.ManifestColumn>,
        scoped: CandidateScope.Statement = CandidateScope.Statement(statement(file), emptyMap()),
    ): ReadResult =
        slots.withPermit {
            checks.ensureAllowed()
            try {
                door
                    .read(
                        DoorRead(
                            context.id,
                            island,
                            context.world,
                            storage,
                            scoped.sql,
                            bindings(params) + scoped.bindings,
                            context.identity,
                            context.modelFp,
                            context.readCeiling,
                            columns,
                            context.timeout,
                            context.recipe.recipe.objectsRead
                                .toSet(),
                        ),
                    ).also {
                        observedWarnings.merge(island, it.warnings.toSet()) { earlier, later -> earlier + later }
                        checks.read(island, it)
                        checks.scopePassed(island)
                    }
            } catch (e: org.tatrman.automation.api.AutomationException) {
                checks.doorRefused(island, e)
            }
        }

    private fun candidateStatement(
        island: String,
        output: ManifestOutput,
        sql: String,
    ): CandidateScope.Statement =
        if (island ==
            context.recipe.recipe.roles
                ?.candidates
        ) {
            CandidateScope.wrap(sql, output.columns, context.keys)
        } else {
            CandidateScope.Statement(sql, emptyMap())
        }

    private fun bindings(names: List<String>): Map<String, BoundValue> =
        names.associateWith {
            requireNotNull(context.bindings[it]) { "unbound declared parameter $it" }
        }

    private fun statement(file: String): String =
        requireNotNull(context.recipe.statements[file]) {
            "unloaded statement $file"
        }

    private fun filter(table: Table): Table {
        val keys = context.keys ?: return table
        val names =
            context.recipe.recipe.key
                .map { it.substringAfterLast('.') }
        val indices =
            names.map { name ->
                table.columns.indexOfFirst { it.name == name }.also {
                    require(
                        it >= 0,
                    ) { "keyed output is missing $name" }
                }
            }

        fun canonical(value: Any?): Any? =
            if (value is Number) java.math.BigDecimal(value.toString()).stripTrailingZeros() else value
        val allowed = keys.map { key -> names.map { canonical(key.getValue(it)) } }.toSet()
        return table.copy(rows = table.rows.filter { row -> indices.map { canonical(row[it]) } in allowed })
    }

    override fun close() {
        if (combine.isInitialized()) combine.value.close()
    }
}
