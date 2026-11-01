// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.scope.TagScope
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.health.objectCount
import org.tatrman.metadata.health.redactCredentials
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.layout.LayoutIndex
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.reconcile.ReconciliationResult
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.metadata.source.logModelLoadIssues

/**
 * Service publication policy (#258). Parsing, reconciliation and graph construction remain library-owned.
 * A source load is provisional until reconciliation and publication succeed. Rejected loads never seed the
 * accepted cache, package index or source success stamp. Startup, RPC, HTTP and scheduled refresh share this lock.
 *
 * The library's strict report classifies blocking errors and dropped files. Changed configured sources
 * owning those issues retain their last good contribution; a clean second pass publishes independent changes.
 * Unattributable or persistent errors refuse the transaction, charging only changed configured sources.
 * Rejected versions are reloaded, including an unchanged SHA; startup waits for configured model content.
 */
class GuardedRefresher(
    private val sources: List<ModelSource>,
    private val sourceIds: List<String>,
    private val reconciler: ModelReconciler,
    private val registry: MetadataRegistry,
    private val strictSwap: Boolean,
    private val configuredIds: Set<String>,
    private val board: SourceLoadBoard = SourceLoadBoard(strictSwap = strictSwap),
    private val packageIndex: PackageIndex = PackageIndex().commitOnSwap(registry),
    val allowNonDefaultBranch: Boolean = false,
) {
    init {
        require(sources.size == sourceIds.size && sourceIds.distinct().size == sourceIds.size) {
            "Each source must have one unique slot id"
        }
        require(configuredIds.all { it in sourceIds }) { "Configured ids must be source slots" }
    }

    private val log = LoggerFactory.getLogger(GuardedRefresher::class.java)
    private val mutex = Mutex()
    private val accepted = mutableMapOf<String, SourceSnapshot>()
    private val acceptedOrigins = mutableMapOf<String, SourceOrigin>()
    private val refusals = mutableMapOf<String, Refusal>()

    private data class Refusal(
        val version: String,
        val issues: List<String>,
    )

    @Volatile
    private var publishing: Pair<Model, Map<String, SourceSnapshot>>? = null

    init {
        // Publish health after the registry reference changes, before downstream listeners such as
        // CN promotion observation read the served source SHA. A failed swap invokes no listener.
        registry.addListener { snapshot ->
            publishing?.takeIf { it.first === snapshot.model }?.second?.forEach { (id, source) ->
                recordAccepted(id, source, snapshot.swappedAt)
            }
        }
    }

    data class SourceResult(
        val sourceId: String,
        val success: Boolean,
        val errorMessage: String = "",
        val oldVersion: String = "",
        val newVersion: String = "",
        val snapshotSwapped: Boolean = false,
        val newModelVersion: String = "",
    )

    @Volatile
    private var lastOutcome: RefreshOutcome? = null
    private var lastAttempt: ModelLoadReport? = null
    private var lastSuccess: ModelLoadReport? = null
    private var keptPrevious = false
    private var lastStatus = ""
    private val attempts = linkedMapOf<String, SourceSnapshot>()
    private val managed =
        sourceIds
            .zip(sources)
            .mapNotNull { (id, source) ->
                (source as? ManagedSource)?.let { id to it }
            }.toMap()

    /** Status never waits for remote I/O or reads transaction-owned mutable state. */
    fun status(): RefreshOutcome =
        lastOutcome?.copy(results = emptyList()) ?: synchronized(registry) {
            val snapshot = registry.read()
            RefreshOutcome(
                emptyList(),
                snapshot,
                board.states(),
                null,
                null,
                null,
                false,
                "",
                ready = board.isReady(snapshot?.swappedAt),
            )
        }

    private val publicationListeners = java.util.concurrent.CopyOnWriteArrayList<(RefreshOutcome) -> Unit>()

    fun addPublicationListener(listener: (RefreshOutcome) -> Unit) {
        publicationListeners += listener
    }

    /** Only accepted content on the configured default proves a CN promotion has merged. */
    internal fun promotionCommit(
        sourceId: String?,
        outcome: RefreshOutcome,
    ): String? {
        val default = managed[sourceId]?.git?.defaultBranch ?: return null
        val origin = outcome.sources.firstOrNull { it.id == sourceId }?.origin ?: return null
        return origin.commit.takeIf {
            origin.source == "git" &&
                !origin.fallback &&
                origin.branch == default &&
                it.isNotEmpty()
        }
    }

    private fun report(
        sourceId: String,
        status: String,
        branch: String = "",
        commit: String = "",
        errors: List<String> = emptyList(),
        warnings: List<String> = emptyList(),
        modelFiles: Int = 0,
    ) = ModelLoadReport(
        sourceId,
        board.now().toString(),
        status == "ok",
        status,
        branch,
        commit,
        errors.map(::redactCredentials),
        warnings,
        modelFiles,
    )

    suspend fun refresh(
        sourceId: String,
        force: Boolean,
    ): List<SourceResult> = refreshOutcome(sourceId, force).results

    suspend fun refreshOutcome(
        sourceId: String,
        force: Boolean,
        branch: String = "",
    ): RefreshOutcome {
        // Preserve immediate unknown-id refusal even while another load holds the publication lock.
        if (sourceId.isNotEmpty() && sourceId !in sourceIds && mutex.isLocked) {
            val message = redactCredentials("unknown_source_id: $sourceId")
            return status()
                .copy(
                    results = listOf(SourceResult(sourceId, false, message)),
                    status = "rejected",
                    httpStatus = 400,
                    keptPrevious = false,
                    lastAttempt =
                        report(sourceId, "rejected", branch.trim(), errors = listOf(message)),
                )
        }
        val ctx = currentCoroutineContext()
        val cancelled = { !ctx.isActive }
        if (force) return mutex.withLock { transact(sourceId, branch.trim(), cancelled) }
        if (!mutex.tryLock()) {
            // Reply from the last completed capture; never wait on the in-flight load.
            return status()
                .copy(
                    results = sourceIds.map { SourceResult(it, false, "refresh_in_flight") },
                    status = "rejected",
                    keptPrevious = lastOutcome?.serving != null,
                    lastAttempt =
                        report(sourceId, "rejected", branch.trim(), errors = listOf("refresh_in_flight")),
                )
        }
        return try {
            transact(sourceId, branch.trim(), cancelled)
        } finally {
            mutex.unlock()
        }
    }

    private fun transact(
        sourceId: String,
        branch: String,
        cancelled: () -> Boolean,
    ): RefreshOutcome {
        var target = sourceId
        var refusal = ""
        var httpStatus = 400
        if (target.isNotEmpty() && target !in sourceIds) refusal = "unknown_source_id: $target"
        if (branch.isNotEmpty() && refusal.isEmpty()) {
            if (target.isEmpty()) {
                val candidates = managed.filter { it.key in configuredIds && it.value.git != null }.keys
                if (candidates.size == 1) target = candidates.single() else refusal = "ambiguous_or_missing_git_source"
            }
            val git = managed[target]?.git
            if (refusal.isEmpty() && (git == null || target !in configuredIds)) refusal = "source_is_not_git: $target"
            if (refusal.isEmpty() && branch != git!!.defaultBranch && !allowNonDefaultBranch) {
                refusal =
                    "branch_not_allowed: configured default is '${git.defaultBranch}'; enable METADATA_ALLOW_NON_DEFAULT_BRANCH"
                httpStatus = 409
            }
            if (refusal.isEmpty() && !BranchStorage.validRef(branch)) refusal = "invalid_git_ref: $branch"
        }
        if (refusal.isNotEmpty()) {
            lastAttempt =
                report(target, "rejected", branch, errors = listOf(refusal))
            keptPrevious = board.states().any { it.loadedOnce && (target.isEmpty() || it.id == target) }
            lastStatus = "rejected"
            return capture(listOf(SourceResult(target, false, redactCredentials(refusal))), "rejected", httpStatus)
        }
        val requested = configuredIds.filter { target.isEmpty() || it == target }.toSet()
        val active = managed.filterKeys { it in requested }
        val before =
            board
                .states()
                .filter { it.loadedOnce }
                .map { it.id }
                .toSet()
        attempts.clear()
        val begun = mutableListOf<ManagedSource>()
        var completed = false
        try {
            active.forEach { (id, source) ->
                begun += source
                source.begin(if (id == target) branch else "", initial = id !in accepted)
            }
            var results = doRefresh(target, cancelled)
            val origins = active.mapValues { it.value.attemptedOrigin() }
            active.forEach { (id, source) ->
                val success = results.firstOrNull { it.sourceId == id }?.success == true
                source.finish(success)?.let { failure ->
                    val safe = redactCredentials(failure)
                    board.recordFailure(id, safe)
                    results =
                        results.map {
                            if (it.sourceId ==
                                id
                            ) {
                                it.copy(success = false, errorMessage = it.errorMessage + "; " + safe)
                            } else {
                                it
                            }
                        }
                }
            }
            completed = true
            if (target.isEmpty() || target in configuredIds) {
                val relevant =
                    results
                        .filter { it.sourceId in requested }
                        .ifEmpty { results.filter { target.isEmpty() || it.sourceId == target } }
                val successes = relevant.count { it.success }
                lastStatus =
                    when {
                        relevant.isNotEmpty() && successes == relevant.size -> "ok"
                        successes > 0 -> "partial"
                        else -> "failed"
                    }
                keptPrevious = relevant.any { !it.success && it.sourceId in before }
                val singleOrigin = origins.values.singleOrNull()
                val loaded = attempts.filterKeys { it in requested }.values
                lastAttempt =
                    report(
                        target,
                        lastStatus,
                        singleOrigin?.branch ?: branch,
                        singleOrigin?.commit.orEmpty(),
                        relevant.filter { !it.success }.map { it.errorMessage },
                        loaded.flatMap { it.warnings }.map { it.diagnostic() }.distinct(),
                        loaded.sumOf { it.loadedFiles.size },
                    )
                if (lastStatus == "ok") lastSuccess = lastAttempt
            }
            return capture(results, lastStatus)
        } finally {
            if (!completed) {
                begun.forEach { source ->
                    source.finish(false)?.let { log.error("{}", redactCredentials(it)) }
                }
            }
        }
    }

    private var countedSnapshot: org.tatrman.ttr.metadata.registry.RegistrySnapshot? = null
    private var objectsByKind: Map<String, Int> = emptyMap()
    private var tags: List<String> = emptyList()

    private fun capture(
        results: List<SourceResult>,
        status: String,
        httpStatus: Int = 200,
    ): RefreshOutcome {
        val snapshot = registry.read()
        val states = board.states()
        val served = acceptedOrigins.filterKeys { it in configuredIds && it in accepted }
        val one = served.entries.singleOrNull()
        val origin = one?.value
        val serving =
            snapshot?.takeIf { served.isNotEmpty() }?.let {
                ServingModel(
                    if (one != null) origin!!.source else "mixed",
                    one?.key.orEmpty(),
                    origin?.branch.orEmpty(),
                    origin?.commit.orEmpty(),
                    it.model.version.value,
                    it.swappedAt.toString(),
                    served.values.any { s -> s.fallback },
                )
            }
        if (snapshot !== countedSnapshot) {
            val objects =
                snapshot
                    ?.model
                    ?.objectByQname()
                    ?.values
                    .orEmpty()
            objectsByKind = objects.groupingBy { it.kind }.eachCount().toSortedMap()
            tags = objects.flatMap { it.tags }.toSortedSet().toList()
            countedSnapshot = snapshot
        }
        val previous = lastOutcome
        return RefreshOutcome(
            results.toList(),
            snapshot,
            states,
            serving,
            lastAttempt,
            lastSuccess,
            keptPrevious,
            status,
            httpStatus,
            ready = board.isReady(snapshot?.swappedAt),
            objectsByKind = objectsByKind,
            tags = tags,
        ).also { outcome ->
            lastOutcome = outcome
            if (snapshot != null &&
                (snapshot !== previous?.snapshot || results.any { it.success && it.sourceId in configuredIds })
            ) {
                publicationListeners.forEach { listener ->
                    runCatching { listener(outcome) }.onFailure { log.warn("Publication listener failed", it) }
                }
            }
        }
    }

    private fun doRefresh(
        sourceId: String,
        cancelled: () -> Boolean,
    ): List<SourceResult> {
        val proposed = accepted.toMutableMap()
        val results = linkedMapOf<String, SourceResult>()
        val loaded = linkedMapOf<String, SourceSnapshot>()
        for ((id, source) in sourceIds.zip(sources)) {
            val old = accepted[id]?.version.orEmpty()
            // A rejected initial transaction leaves no cache, including stock roles and vocabulary
            // versions. Bootstrap those internal contributions even on a targeted retry.
            if (sourceId.isNotEmpty() && sourceId != id && (id in accepted || id in configuredIds)) {
                if (id in accepted) results[id] = SourceResult(id, true, oldVersion = old, newVersion = old)
                continue
            }
            try {
                val snapshot = source.load()
                if (cancelled()) throw CancellationException("refresh_cancelled")
                attempts[id] = snapshot
                val empty = id in configuredIds && snapshot.objectCount() == 0
                if (strictSwap && empty) {
                    results[id] =
                        reject(
                            id,
                            old,
                            "strict_swap_rejected",
                            listOf("source '$id': no model objects (empty model source)"),
                            snapshot.version,
                        )
                } else {
                    proposed[id] = snapshot
                    loaded[id] = snapshot
                    results[id] = SourceResult(id, true, oldVersion = old, newVersion = snapshot.version)
                }
            } catch (e: Throwable) {
                results[id] = reject(id, old, "load_failed", listOf(contained(e, cancelled)))
            }
        }

        if (strictSwap && configuredIds.isNotEmpty() && configuredIds.none { it in proposed }) {
            return results.values.toList()
        }
        val changed = registry.read() == null || loaded.any { (id, snap) -> snap.version != accepted[id]?.version }
        if (!changed) {
            // Cache successful same-version sidecar retries without republishing; the next model swap
            // may serve them. Rejected loads never enter this cache.
            loaded.forEach { (id, snapshot) ->
                accepted[id] = snapshot
                recordAccepted(id, snapshot)
            }
            return results.values.toList()
        }
        try {
            var snapshots = sourceIds.mapNotNull { proposed[it] }
            var result = reconciler.reconcile(snapshots)
            if (strictSwap) {
                var blocking = blockingIssues(proposed, snapshots, result)
                if (blocking.isNotEmpty()) {
                    val changedIds = loaded.filter { (id, snap) -> accepted[id]?.version != snap.version }.keys
                    val blamed = changedIds.filter { id -> id in configuredIds && blocking.any { id in it.second } }
                    blamed.forEach { id ->
                        results[id] =
                            reject(
                                id,
                                accepted[id]?.version.orEmpty(),
                                "strict_swap_rejected",
                                blocking
                                    .filter {
                                        id in
                                            it.second
                                    }.map { it.first },
                                loaded.getValue(id).version,
                            )
                        loaded.remove(id)
                        accepted[id]?.let { proposed[id] = it } ?: proposed.remove(id)
                    }
                    blocking =
                        if (blamed.isEmpty()) {
                            blocking
                        } else {
                            snapshots = sourceIds.mapNotNull { proposed[it] }
                            result = reconciler.reconcile(snapshots)
                            blockingIssues(proposed, snapshots, result)
                        }
                    if (blocking.isNotEmpty()) {
                        val issues = blocking.map { it.first }
                        loaded.keys.filter { it in changedIds && it in configuredIds }.forEach { id ->
                            results[id] =
                                reject(
                                    id,
                                    accepted[id]?.version.orEmpty(),
                                    "reconcile_rejected",
                                    issues,
                                    loaded.getValue(id).version,
                                )
                        }
                        return results.values.toList()
                    }
                    if (loaded.none { (id, snap) -> snap.version != accepted[id]?.version } &&
                        registry.read() != null
                    ) {
                        loaded.forEach { (id, snapshot) ->
                            accepted[id] = snapshot
                            recordAccepted(id, snapshot)
                        }
                        return results.values.toList()
                    }
                }
                if (configuredIds.isNotEmpty() && configuredIds.none { it in proposed }) {
                    log.error("No configured model source accepted yet; publishing nothing")
                    return results.values.toList()
                }
            }
            val scopeWarnings =
                TagScope.loadWarnings(result.model)
            val graph = ModelGraph.build(result.model)
            val layouts = LayoutIndex.build(snapshots, result.model)
            if (cancelled()) throw CancellationException("refresh_cancelled")
            packageIndex.stageAccepted(proposed)
            publishing = result.model to loaded
            try {
                registry.swap(
                    result.model,
                    graph,
                    result.warnings + result.errors + scopeWarnings,
                    layouts,
                    files = result.files,
                    exportSidecars =
                        snapshots.sortedBy { it.priority }.flatMap { it.exportSidecars.entries }.associate {
                            it
                                .toPair()
                        },
                )
            } finally {
                publishing = null
            }
            accepted.clear()
            accepted.putAll(proposed)
            log.info("Metadata model published: version={} strict_swap={}", result.model.version.value, strictSwap)
            log.logModelLoadIssues(result.errors, result.warnings + scopeWarnings)
            return results.values.map {
                if (it.success &&
                    it.sourceId in loaded
                ) {
                    it.copy(snapshotSwapped = true, newModelVersion = result.model.version.value)
                } else {
                    it
                }
            }
        } catch (e: Throwable) {
            val message = contained(e, cancelled)
            loaded.keys
                .filter { id ->
                    id in configuredIds && loaded.getValue(id).version != accepted[id]?.version
                }.forEach { id ->
                    results[id] =
                        reject(
                            id,
                            accepted[id]?.version.orEmpty(),
                            "reconcile_failed",
                            listOf(message),
                            loaded.getValue(id).version,
                        )
                }
            return results.values.toList()
        }
    }

    /**
     * The library's strict verdict (the `ttr-metadata load --strict` gate) on [snapshots]: each ERROR issue
     * or dropped file, attributed to source slots by file, else by the reconciler's source id.
     * Keep all owners of shared paths; a fallback snapshot may have a different source id from its slot.
     */
    private fun blockingIssues(
        publishingCandidate: Map<String, SourceSnapshot>,
        snapshots: List<SourceSnapshot>,
        result: ReconciliationResult,
    ): List<Pair<String, Set<String>>> {
        val report =
            checkNotNull(
                MetadataLoader(snapshots.map { s -> ModelSource { s } }).load(LoadOptions(strict = true)).report,
            )
        val owner = mutableMapOf<String, MutableSet<String>>()
        val slotsBySource = mutableMapOf<String, MutableSet<String>>()
        publishingCandidate.forEach { (slot, snapshot) ->
            val slots = setOf(slot)
            slotsBySource.getOrPut(snapshot.sourceId) { mutableSetOf() }.addAll(slots)
            val files =
                snapshot.loadedFiles.map { it.storageFile.path } +
                    (snapshot.errors + snapshot.droppedFiles + snapshot.warnings).map { it.file }
            files.filter { it.isNotEmpty() && it != "<reconcile>" }.forEach { file ->
                owner.getOrPut(file) { mutableSetOf() }.addAll(slots)
            }
        }

        fun owners(
            file: String?,
            message: String,
        ): Set<String> =
            owner[file]?.toSet()
                ?: (result.errors + result.warnings)
                    .filter { it.message == message }
                    .flatMap { slotsBySource[it.sourceId].orEmpty() }
                    .toSet()
        val errors =
            report.issues.filter { it.severity == LoadIssue.Severity.ERROR }.map {
                redactCredentials("${it.code} ${it.file}:${it.line}: ${it.message}") to owners(it.file, it.message)
            }
        val dropped = report.droppedFiles.map { redactCredentials("dropped-file $it") to owners(it, "") }
        return (errors + dropped).distinct()
    }

    private fun contained(
        e: Throwable,
        cancelled: () -> Boolean,
    ): String {
        if (e is CancellationException && cancelled()) throw e
        if (e is VirtualMachineError && e !is StackOverflowError) throw e
        return "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
    }

    private fun recordAccepted(
        id: String,
        snapshot: SourceSnapshot,
        at: java.time.Instant? = null,
    ) {
        refusals.remove(id)
        val origin = managed[id]?.origin ?: SourceOrigin("unknown")
        acceptedOrigins[id] = origin
        if (at == null) board.record(id, snapshot, origin = origin) else board.record(id, snapshot, at, origin)
    }

    private fun reject(
        id: String,
        oldVersion: String,
        code: String,
        issues: List<String>,
        attemptedVersion: String = "",
    ): SourceResult {
        val safeIssues = issues.map(::redactCredentials)
        val message = "$code: ${safeIssues.joinToString("; ")}"
        board.recordFailure(id, message, safeIssues)
        val key = Refusal(attemptedVersion, safeIssues)
        if (refusals.put(id, key) == key) {
            log.error("Source '{}' still refusing '{}', {} issues, see /status", id, attemptedVersion, safeIssues.size)
        } else {
            log.error(
                "Source '{}' rejected version '{}'; retaining last good version '{}': {}",
                id,
                attemptedVersion,
                oldVersion,
                message,
            )
        }
        return SourceResult(id, false, message, oldVersion = oldVersion)
    }
}

internal fun LoadWarning.diagnostic(): String = redactCredentials("source '$sourceId' $file:$line:$column: $message")
