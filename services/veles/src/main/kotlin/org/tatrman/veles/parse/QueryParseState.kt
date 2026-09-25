// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.parse

import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.QualifiedName
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Live, mutable parse state for the queries in the *current* model snapshot
 * (Section F / DF-M05). Reset on every model swap, then updated in place by the
 * [QueryParseWorker] as parse jobs complete. gRPC handlers capture a single read
 * at request entry — `get(...)` returns a consistent value per call.
 *
 * The model's own [org.tatrman.ttr.metadata.model.Query.parseStatus] stays the *initial*
 * value (PENDING for everything loaded from sources, since sources don't parse);
 * this holder is the authoritative live view layered on top of it.
 */
class QueryParseState {
    /**
     * One model's parse state, replaced wholesale by [reset]. Every read and write names the model
     * version it is about, and an epoch answers only for its own: GetSnapshot ships these results as
     * the plans the translator expands, so a result must never cross from one model to another.
     * Two paths used to let it: the swap window (the registry publishes the new model, then runs its
     * listeners — the search-index rebuild first, this reset after it), and a previous model's parse
     * job finishing after this model's job for the same qname (jobs are never cancelled).
     */
    private class Epoch(
        val modelVersion: String?,
        val byQname: Map<QualifiedName, AtomicReference<ParseStatus>>,
    )

    private val current = AtomicReference(Epoch(modelVersion = null, byQname = emptyMap()))

    // GH #112 — bumped on every reset and every recorded outcome, and folded into the GetSnapshot
    // ETag. Canonical forms land here *after* the model swap, while the model version is fixed at
    // swap time: an ETag of the bare version would let a consumer that polled during the parse
    // window (the translate service's handle) keep a snapshot without them for as long as the
    // model lives.
    private val generation = AtomicLong(0)

    /**
     * Start tracking [modelVersion]: one PENDING entry per qname. Call on model swap, before
     * enqueueing parse jobs. Whatever was tracked for the previous model is dropped whole.
     */
    fun reset(
        modelVersion: String,
        qnames: Collection<QualifiedName>,
    ) {
        current.set(
            Epoch(modelVersion, qnames.associateWith { AtomicReference<ParseStatus>(ParseStatus.ParsePending) }),
        )
        generation.incrementAndGet()
    }

    /**
     * Record a parse outcome for [qname] under [modelVersion]. No-op if that is not the tracked
     * model (a stale job from before a swap) or [qname] is not in it.
     */
    fun set(
        modelVersion: String,
        qname: QualifiedName,
        status: ParseStatus,
    ) {
        val epoch = current.get()
        if (epoch.modelVersion != modelVersion) return
        val ref = epoch.byQname[qname] ?: return
        ref.set(status)
        generation.incrementAndGet()
    }

    /**
     * A token that advances whenever this state changes (a reset or a parse outcome). It settles
     * once the [QueryParseWorker] has finished, so an ETag built from it settles too.
     */
    fun generation(): Long = generation.get()

    /**
     * Live status for [qname] in model [modelVersion], or null if that model is not the tracked one
     * (yet — the swap window) or [qname] is not in it. Null ⇒ the caller falls back to the model's
     * stored status (PENDING, no plan).
     */
    fun get(
        modelVersion: String,
        qname: QualifiedName,
    ): ParseStatus? {
        val epoch = current.get()
        return if (epoch.modelVersion == modelVersion) epoch.byQname[qname]?.get() else null
    }

    data class Counts(
        val parsed: Int,
        val pending: Int,
        val failed: Int,
    )

    /** Counts for model [modelVersion], or null if that model is not the tracked one (yet). */
    fun counts(modelVersion: String): Counts? {
        val epoch = current.get()
        if (epoch.modelVersion != modelVersion) return null
        var parsed = 0
        var pending = 0
        var failed = 0
        for (ref in epoch.byQname.values) {
            when (ref.get()) {
                is ParseStatus.ParseSuccess -> parsed++
                is ParseStatus.ParsePending -> pending++
                is ParseStatus.ParseFailure -> failed++
            }
        }
        return Counts(parsed = parsed, pending = pending, failed = failed)
    }
}
