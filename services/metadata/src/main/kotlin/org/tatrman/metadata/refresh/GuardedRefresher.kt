// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.tatrman.metadata.grpc.PackageIndex
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

    suspend fun refresh(
        sourceId: String,
        force: Boolean,
    ): List<SourceResult> {
        if (sourceId.isNotEmpty() && sourceId !in sourceIds) {
            return listOf(SourceResult(sourceId, false, "unknown_source_id: $sourceId"))
        }
        val ctx = currentCoroutineContext()
        val cancelled = { !ctx.isActive }
        if (force) return mutex.withLock { doRefresh(sourceId, cancelled) }
        if (!mutex.tryLock()) return sourceIds.map { SourceResult(it, false, "refresh_in_flight") }
        return try {
            doRefresh(sourceId, cancelled)
        } finally {
            mutex.unlock()
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
            val graph = ModelGraph.build(result.model)
            val layouts = LayoutIndex.build(snapshots, result.model)
            packageIndex.stageAccepted(proposed)
            publishing = result.model to loaded
            try {
                registry.swap(
                    result.model,
                    graph,
                    result.warnings + result.errors,
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
            log.logModelLoadIssues(result.errors, result.warnings)
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
        if (at == null) board.record(id, snapshot) else board.record(id, snapshot, at)
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
