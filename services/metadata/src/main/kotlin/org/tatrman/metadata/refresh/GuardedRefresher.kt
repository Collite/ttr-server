// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.health.objectCount
import org.tatrman.metadata.health.redactCredentials
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.layout.LayoutIndex
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
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
 * Parse failures retain only that source's last good contribution; independent sources may still advance.
 * Reconciliation errors can span sources: conservatively reject the entire proposed transaction, retaining
 * every served contribution. The next attempt re-loads rejected versions, including an unchanged SHA.
 */
class GuardedRefresher(
    private val sources: List<ModelSource>,
    private val sourceIds: List<String>,
    private val reconciler: ModelReconciler,
    private val registry: MetadataRegistry,
    private val board: SourceLoadBoard = SourceLoadBoard(),
    private val packageIndex: PackageIndex = PackageIndex().commitOnSwap(registry),
) {
    init {
        require(sources.size == sourceIds.size && sourceIds.distinct().size == sourceIds.size) {
            "Each source must have one unique slot id"
        }
    }

    private val strictSwap: Boolean get() = board.strictSwap
    private val log = LoggerFactory.getLogger(GuardedRefresher::class.java)
    private val mutex = Mutex()
    private val accepted = mutableMapOf<String, SourceSnapshot>()

    @Volatile
    private var publishing: Pair<Model, Map<String, SourceSnapshot>>? = null

    init {
        // Publish health after the registry reference changes, before downstream listeners such as
        // CN promotion observation read the served source SHA. A failed swap invokes no listener.
        registry.addListener { snapshot ->
            publishing?.takeIf { it.first === snapshot.model }?.second?.forEach { (id, source) ->
                board.record(id, source, snapshot.swappedAt)
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
        if (force) return mutex.withLock { doRefresh(sourceId) }
        if (!mutex.tryLock()) return sourceIds.map { SourceResult(it, false, "refresh_in_flight") }
        return try {
            doRefresh(sourceId)
        } finally {
            mutex.unlock()
        }
    }

    private fun doRefresh(sourceId: String): List<SourceResult> {
        val proposed = accepted.toMutableMap()
        val results = linkedMapOf<String, SourceResult>()
        val loaded = linkedMapOf<String, SourceSnapshot>()
        for ((id, source) in sourceIds.zip(sources)) {
            val old = accepted[id]?.version.orEmpty()
            if (sourceId.isNotEmpty() && sourceId != id) {
                results[id] = SourceResult(id, true, oldVersion = old, newVersion = old)
                continue
            }
            try {
                val snapshot = source.load()
                val issues = (snapshot.errors + snapshot.droppedFiles).distinct().map { it.diagnostic() }
                val empty = board.states().any { it.id == id } && snapshot.objectCount() == 0
                if (strictSwap && (issues.isNotEmpty() || empty)) {
                    val errors = issues.ifEmpty { listOf("source '$id': no model objects (empty model source)") }
                    results[id] = reject(id, old, "strict_swap_rejected", errors)
                } else {
                    proposed[id] = snapshot
                    loaded[id] = snapshot
                    results[id] = SourceResult(id, true, oldVersion = old, newVersion = snapshot.version)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results[id] =
                    reject(id, old, "load_failed", listOf("${e.javaClass.simpleName}: ${e.message.orEmpty()}"))
            }
        }

        val changed = registry.read() == null || loaded.any { (id, snap) -> snap.version != accepted[id]?.version }
        if (!changed) {
            // Cache successful same-version sidecar retries without republishing; the next model swap
            // may serve them. Rejected loads never enter this cache.
            loaded.forEach { (id, snapshot) ->
                accepted[id] = snapshot
                board.record(id, snapshot)
            }
            return results.values.toList()
        }
        val snapshots = sourceIds.mapNotNull { proposed[it] }
        try {
            val result = reconciler.reconcile(snapshots)
            if (strictSwap && result.errors.isNotEmpty()) {
                val issues = result.errors.map { it.diagnostic() }.distinct()
                loaded.keys.forEach { id ->
                    results[id] = reject(id, accepted[id]?.version.orEmpty(), "reconcile_rejected", issues)
                }
                return results.values.toList()
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
                if (it.success) it.copy(snapshotSwapped = true, newModelVersion = result.model.version.value) else it
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loaded.keys.forEach { id ->
                results[id] =
                    reject(id, accepted[id]?.version.orEmpty(), "reconcile_failed", listOf(e.message.orEmpty()))
            }
            return results.values.toList()
        }
    }

    private fun reject(
        id: String,
        oldVersion: String,
        code: String,
        issues: List<String>,
    ): SourceResult {
        val safeIssues = issues.map(::redactCredentials)
        val message = "$code: ${safeIssues.joinToString("; ")}"
        board.recordFailure(id, message, safeIssues)
        log.error("Source '{}' rejected; retaining last good version '{}': {}", id, oldVersion, message)
        return SourceResult(id, false, message, oldVersion = oldVersion)
    }
}

internal fun LoadWarning.diagnostic(): String = redactCredentials("source '$sourceId' $file:$line:$column: $message")
