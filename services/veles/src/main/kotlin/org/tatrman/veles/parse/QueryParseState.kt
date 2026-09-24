// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.parse

import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.QualifiedName
import java.util.concurrent.ConcurrentHashMap
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
    private val byQname = ConcurrentHashMap<QualifiedName, AtomicReference<ParseStatus>>()

    // GH #112 — bumped on every reset and every recorded outcome, and folded into the GetSnapshot
    // ETag. Canonical forms land here *after* the model swap, while the model version is fixed at
    // swap time: an ETag of the bare version would let a consumer that polled during the parse
    // window (the translate service's handle) keep a snapshot without them for as long as the
    // model lives.
    private val generation = AtomicLong(0)

    /** Reset to one PENDING entry per qname. Call on model swap, before enqueueing parse jobs. */
    fun reset(qnames: Collection<QualifiedName>) {
        byQname.clear()
        for (qn in qnames) byQname[qn] = AtomicReference(ParseStatus.ParsePending)
        generation.incrementAndGet()
    }

    /** Record a parse outcome. No-op if [qname] isn't tracked (stale job after a swap). */
    fun set(
        qname: QualifiedName,
        status: ParseStatus,
    ) {
        val ref = byQname[qname] ?: return
        ref.set(status)
        generation.incrementAndGet()
    }

    /**
     * A token that advances whenever this state changes (a reset or a parse outcome). It settles
     * once the [QueryParseWorker] has finished, so an ETag built from it settles too.
     */
    fun generation(): Long = generation.get()

    /** Live status for [qname], or null if not tracked (caller falls back to the model's stored status). */
    fun get(qname: QualifiedName): ParseStatus? = byQname[qname]?.get()

    data class Counts(
        val parsed: Int,
        val pending: Int,
        val failed: Int,
    )

    fun counts(): Counts {
        var parsed = 0
        var pending = 0
        var failed = 0
        for (ref in byQname.values) {
            when (ref.get()) {
                is ParseStatus.ParseSuccess -> parsed++
                is ParseStatus.ParsePending -> pending++
                is ParseStatus.ParseFailure -> failed++
            }
        }
        return Counts(parsed = parsed, pending = pending, failed = failed)
    }
}
