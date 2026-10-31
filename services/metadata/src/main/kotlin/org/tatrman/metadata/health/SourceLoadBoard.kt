// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.health

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.ProjectDef
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.metadata.refresh.SourceOrigin
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ttr-server#180 — what every configured model source has done so far, for `/ready`, `/status`
 * and `/metrics`.
 *
 * Readiness is about the model Metadata was told to serve, not about having *a* model: Metadata is
 * ready once every configured source has been accepted at least once and that model is swapped in. A load counts when it returns a
 * snapshot with at least one model object; [org.tatrman.metadata.refresh.GuardedRefresher] additionally
 * rejects dropped files and ERROR issues in the library's strict report. A load that throws (bad token, wrong branch, no
 * network) or that finds nothing (an empty repository or a wrong sub-path) is a failure, and the
 * failure stays visible in `/status` and in `metadata_model_source_load_failures_total`. Once a
 * source has loaded (`metadata_model_source_loaded` is 1), a later failed refresh does not take readiness away: the refresher keeps
 * serving that source's last good snapshot.
 *
 * A source whose fallback is explicitly configured counts as loaded when the fallback served,
 * and reports `serving_fallback` (the fallback is opt-in; the default configuration has none).
 *
 * The built-in stock roles are not a configured source and are never registered here.
 */
class SourceLoadBoard(
    private val meterRegistry: MeterRegistry? = null,
    private val clock: Clock = Clock.systemUTC(),
    val strictSwap: Boolean = true,
) {
    /** One configured source, as `/status` reports it. */
    data class SourceLoadState(
        val id: String,
        val description: String,
        val loadedOnce: Boolean = false,
        val lastAttemptAt: Instant? = null,
        val firstSuccessAt: Instant? = null,
        val lastSuccessAt: Instant? = null,
        val lastError: String = "",
        val lastErrorAt: Instant? = null,
        val issues: List<String> = emptyList(),
        val consecutiveFailures: Int = 0,
        val failuresTotal: Long = 0,
        val servingFallback: Boolean = false,
        val version: String = "",
        val objectCount: Int = 0,
        val origin: SourceOrigin = SourceOrigin("unknown"),
    )

    private val order = CopyOnWriteArrayList<String>()
    private val states = ConcurrentHashMap<String, SourceLoadState>()
    private val failureCounters = ConcurrentHashMap<String, Counter>()

    /** Declare a configured source. [description] must already be free of credentials. */
    fun register(
        id: String,
        description: String,
    ) {
        if (states.putIfAbsent(id, SourceLoadState(id = id, description = description)) == null) {
            order += id
            meterRegistry?.let { registry ->
                Gauge
                    .builder("metadata.model.source.loaded") { if (states[id]?.loadedOnce == true) 1.0 else 0.0 }
                    .description("1 once the configured model source has loaded at least once")
                    .tag("source", id)
                    .register(registry)
                failureCounters[id] =
                    Counter
                        .builder("metadata.model.source.load.failures")
                        .description("Model source loads that failed or were refused by the publication policy")
                        .tag("source", id)
                        .register(registry)
            }
        }
    }

    /** Test-only load adapter; production records acceptance through GuardedRefresher after publication. */
    fun recording(
        id: String,
        description: String,
        source: ModelSource,
    ): ModelSource {
        register(id, description)
        return ModelSource {
            val snapshot =
                try {
                    source.load()
                } catch (e: Exception) {
                    recordFailure(id, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                    throw e
                }
            record(id, snapshot)
            snapshot
        }
    }

    /**
     * True once every registered source has loaded at least once AND the model swapped in at
     * [modelSwappedAt] was reconciled after those loads — so `/ready` never turns UP in the moment
     * between a source loading and its model being served. False when no source is registered.
     */
    fun isReady(modelSwappedAt: Instant?): Boolean =
        modelSwappedAt != null &&
            order.isNotEmpty() &&
            order.all { id -> states[id]?.firstSuccessAt?.let { !it.isAfter(modelSwappedAt) } == true }

    /** Ids of the registered sources that have not loaded yet, in registration order. */
    fun notLoaded(): List<String> = order.filter { states[it]?.loadedOnce != true }

    /** Every registered source, in registration order. */
    fun states(): List<SourceLoadState> = order.mapNotNull { states[it] }

    fun record(
        id: String,
        snapshot: SourceSnapshot,
        publishedAt: Instant = clock.instant(),
        origin: SourceOrigin? = null,
    ) {
        val objects = snapshot.objectCount()
        if (objects == 0) {
            // Permissive mode may publish an empty contribution; keep health failed while provenance names it.
            origin?.let { value -> states.computeIfPresent(id) { _, state -> state.copy(origin = value) } }
            recordFailure(
                id,
                "the source loaded no model objects (an empty repository, or a wrong branch or sub-path?)",
            )
            return
        }
        val now = publishedAt
        val fallbackNote = snapshot.warnings.firstOrNull { it.file == FALLBACK_MARKER }
        states.computeIfPresent(id) { _, s ->
            s.copy(
                loadedOnce = true,
                lastAttemptAt = now,
                firstSuccessAt = s.firstSuccessAt ?: now,
                lastSuccessAt = now,
                lastError = redactCredentials(fallbackNote?.message.orEmpty()),
                lastErrorAt = if (fallbackNote != null) now else null,
                issues = emptyList(),
                consecutiveFailures = 0,
                servingFallback = fallbackNote != null,
                version = snapshot.version,
                objectCount = objects,
                origin = origin ?: s.origin,
            )
        }
    }

    fun recordFailure(
        id: String,
        message: String,
        issues: List<String> = listOf(message),
    ) {
        val now = clock.instant()
        states.computeIfPresent(id) { _, s ->
            s.copy(
                lastAttemptAt = now,
                lastError = redactCredentials(message),
                lastErrorAt = now,
                issues = issues.map(::redactCredentials),
                consecutiveFailures = s.consecutiveFailures + 1,
                failuresTotal = s.failuresTotal + 1,
            )
        }
        failureCounters[id]?.increment()
    }

    companion object {
        /** The `LoadWarning.file` that `FallbackSource` stamps on a snapshot its fallback served. */
        const val FALLBACK_MARKER = "<fallback>"
    }
}

/** Every model object a snapshot contributes, of any kind. */
internal fun SourceSnapshot.objectCount(): Int =
    maxOf(
        tables.size + views.size + procedures.size + foreignKeys.size + entities.size + relations.size +
            mappings.size + queries.size + roles.size + drillMaps.size + areas.size + worlds.size +
            rowSchemas.size + automation.size,
        loadedFiles.sumOf { f -> f.definitions.count { it !is LexiconEntryDef && it !is ProjectDef } },
    )

private val URI_USERINFO = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\s]+@""")

/** Replaces the user-info of every URI in [text] (`user:token@`) with `***@`. */
internal fun redactCredentials(text: String): String = text.replace(URI_USERINFO, "$1***@")
