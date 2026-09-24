// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.OverlayRequest
import org.tatrman.fuzzy.core.OverlayStore
import org.tatrman.fuzzy.core.OverlayVerdict
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.fuzzy.core.TextNormalizer
import org.tatrman.ttr.snapshot.SnapshotId
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * RV-P7.3 T3 — the LEARNED layer, read from an overlay archive.
 *
 * A deliberate **sibling of [LexiconArchiveSource]**, not a variation on it: read bytes →
 * [SnapshotId] → skip if unchanged → parse → atomic swap, with every failure logged and none of
 * them fatal. That discipline is already proved by the declared layer and by the two-clock refresh
 * that drives it, and the overlay has exactly the same job. What differs is only who writes the
 * file — the estate build writes the lexicon, the Golem writes this one at runtime — and this
 * class is deliberately ignorant of that. It reads a path.
 *
 * **Both halves of the layer come from this one loaded document**, which is why the store is one
 * object: [learned] is the POSITIVE entries as candidates, [consult] is the NEGATIVE ones as
 * suppressions, and [version] names the overlay both came from. Two objects reading the same file
 * could serve a suppression from one version against a candidate from another.
 *
 * **Missing or unreadable is not fatal.** An estate that has learned nothing yet — every estate,
 * on its first day — is a supported deployment, not a broken one.
 */
class OverlayArchiveSource(
    private val archivePath: Path,
) : OverlayStore {
    private val logger = LoggerFactory.getLogger(OverlayArchiveSource::class.java)

    @Volatile
    private var loaded: Loaded? = null

    private companion object {
        /**
         * The one consult rule, shared by the live source and by a [Pinned] view of it.
         *
         * **Asked of the query AND of every candidate on the table**, which is what
         * [OverlayRequest.candidates] is for and what this originally failed to do. The two halves
         * of the layer reach a term by different routes: a POSITIVE is merged into the index and so
         * is found by the engine, fuzzily, through whatever the user actually typed; a NEGATIVE is
         * a map lookup and has no engine behind it. Keyed on the query alone, the halves disagree —
         * `cerpacich stanic` reaches the learned positive and walks straight past the refusal for
         * the same term, so two users' explicit "no" is silently not applied while their "yes" is.
         *
         * Reading the candidates closes it without a second engine: retrieval has already decided
         * which terms this query reaches, and [FuzzyMatchResult.candidate] is the term it reached.
         * A suppression that fires through a candidate is therefore exactly as fuzzy as the match
         * that put the candidate on the table — no more, and never less.
         *
         * Over-reach is the safe direction here and bounded by design: a suppressed candidate is
         * flagged, never removed (see [OverlayVerdict.suppressedTargets]), so the cost of firing
         * too widely is an auto-bind the resolver has to ask about, and the cost of firing too
         * narrowly is a binding two people already rejected.
         */
        fun consult(
            at: Loaded?,
            request: OverlayRequest,
        ): OverlayVerdict {
            val suppressions = at?.suppressions ?: return OverlayVerdict.EMPTY
            if (suppressions.isEmpty()) return OverlayVerdict.EMPTY

            val reached =
                buildSet {
                    add(TextNormalizer.fold(request.term))
                    request.candidates.forEach { add(TextNormalizer.fold(it.candidate)) }
                }
            val suppressed = reached.flatMapTo(mutableSetOf()) { suppressions[it].orEmpty() }
            return if (suppressed.isEmpty()) OverlayVerdict.EMPTY else OverlayVerdict(suppressed)
        }

        const val POSITIVE = "POSITIVE"
        const val NEGATIVE = "NEGATIVE"
        const val ACTIVE = "ACTIVE"

        /** RV-P7.2's lifecycle: PROPOSED and INVALIDATED never reach a matcher. */
        val SERVABLE = setOf(ACTIVE, "PROMOTION_CANDIDATE")
    }

    private class Loaded(
        val archiveId: String,
        val doc: OverlayDoc,
        /** POSITIVE + servable, by category (= target ref). */
        val candidates: Map<String, List<Candidate>>,
        /** **Folded** term → the refs this estate has learned it does NOT mean (see [toSuppressions]). */
        val suppressions: Map<String, Set<String>>,
    )

    /**
     * The **store's** version, for the RV-39 tuple — not the archive's content id.
     *
     * Null until an archive is actually loaded, which keeps the contract exact: `overlay_version`
     * is absent for an estate with no overlay, and a pre-P7 estate parses unchanged. It is a
     * pure accessor — `layerVersions()` is called on every response, including the error path, so
     * touching the disk here would put a file read and a hash on the hot path of every question.
     */
    override fun version(): String? = loaded?.doc?.version?.toString()

    /**
     * The refresh clock: the archive's content id, or `""` when there is nothing to load.
     *
     * **This is the one method that reads the disk**, called once per refresh interval by
     * `StringRepository`, exactly as `LexiconArchiveSource.hash()` is. Stable when absent, so an
     * estate without an overlay does not reload on every tick.
     */
    override fun hash(): String = load()?.archiveId ?: ""

    override suspend fun learned(): Map<String, List<Candidate>> = loaded?.candidates ?: emptyMap()

    override suspend fun consult(request: OverlayRequest): OverlayVerdict = consult(loaded, request)

    /**
     * RV-P7.3 T4 — the overlay as it stands right now, frozen.
     *
     * `StringRepository` publishes one of these together with the index it built from the same
     * archive, and the matcher answers a whole request from it. Without the freeze, a reload
     * landing between two spans of one BatchMatch would answer them from two overlays and report a
     * single `overlay_version` for both — a response that cannot be reproduced from its own RV-39
     * tuple, which is the one thing the tuple is for.
     */
    override fun pinned(): OverlayStore = Pinned(loaded)

    /** One frozen overlay. Nothing here reads [loaded], which is the entire point. */
    private class Pinned(
        private val at: Loaded?,
    ) : OverlayStore {
        override fun version(): String? = at?.doc?.version?.toString()

        override fun hash(): String = at?.archiveId ?: ""

        override suspend fun learned(): Map<String, List<Candidate>> = at?.candidates ?: emptyMap()

        override suspend fun consult(request: OverlayRequest): OverlayVerdict = consult(at, request)

        /** Already frozen — pinning a pin is the same pin. */
        override fun pinned(): OverlayStore = this
    }

    /**
     * Reads and parses, reusing what is loaded when the bytes hash to the same id.
     *
     * Returns null — never throws — for absent, unreadable, wrong-kind or malformed archives, each
     * logged once per distinct cause. A broken overlay must be loud in the log and invisible to a
     * query: the alternative is an estate silently losing its learned vocabulary, or a matcher
     * refusing to answer at all because a *third* layer is malformed.
     */
    private fun load(): Loaded? {
        if (!archivePath.exists()) {
            loaded?.let { logger.warn("Overlay archive disappeared from {} — keeping the loaded one", archivePath) }
            return loaded
        }

        val bytes =
            try {
                archivePath.readBytes()
            } catch (e: Exception) {
                logger.warn("Overlay archive at {} is unreadable: {}", archivePath, e.message)
                return loaded
            }

        val id = SnapshotId.of(bytes)
        loaded?.let { if (it.archiveId == id) return it }

        val contents =
            when (val read = SnapshotReader.read(bytes)) {
                is SnapshotReadResult.Ok -> read.contents
                is SnapshotReadResult.Failure -> {
                    logger.warn("Overlay archive at {} is not a readable archive: {}", archivePath, read.reason)
                    return loaded
                }
            }

        if (contents.manifest.kind != OverlayArchive.KIND) {
            logger.warn(
                "Archive at {} is kind='{}', expected '{}' — not an overlay archive",
                archivePath,
                contents.manifest.kind,
                OverlayArchive.KIND,
            )
            return loaded
        }

        val json =
            contents.docs[OverlayArchive.OVERLAY] ?: run {
                logger.warn("Overlay archive at {} has no {}", archivePath, OverlayArchive.OVERLAY)
                return loaded
            }

        val doc =
            try {
                OverlayArchive.JSON.decodeFromString<OverlayDoc>(json)
            } catch (e: Exception) {
                logger.warn(
                    "Overlay archive at {} has an undecodable {}: {}",
                    archivePath,
                    OverlayArchive.OVERLAY,
                    e.message,
                )
                return loaded
            }

        if (doc.schema != OverlayArchive.SCHEMA) {
            // Not a parse failure — a *different contract*. Serving it would mean guessing what a
            // future producer meant by fields this build reads with today's meaning.
            logger.warn(
                "Overlay archive at {} declares schema '{}', this build reads '{}' — not loaded",
                archivePath,
                doc.schema,
                OverlayArchive.SCHEMA,
            )
            return loaded
        }

        return Loaded(id, doc, doc.toCandidates(), doc.toSuppressions()).also {
            loaded = it
            logger.info(
                "Overlay loaded for estate {}: version={} ({} learned over {} targets, {} suppressed terms, archive={})",
                doc.estateId,
                doc.version,
                it.candidates.values.sumOf { rows -> rows.size },
                it.candidates.size,
                it.suppressions.size,
                id,
            )
        }
    }

    /**
     * POSITIVE + servable entries as candidates, keyed by target ref (the declared layer's category
     * convention).
     *
     * **Status is re-checked here, not trusted from the export** (T2(e)). An INVALIDATED entry is
     * one RV-20's snapshot build already retired, and a transport that lags — a Golem that has not
     * re-exported yet, an archive a pod has not picked up — must not be able to resurrect it. The
     * exporter ships the whole store precisely so this decision is made by the thing doing the
     * serving.
     */
    private fun OverlayDoc.toCandidates(): Map<String, List<Candidate>> =
        entries
            .asSequence()
            .filter { it.polarity == POSITIVE && it.status in SERVABLE }
            .mapNotNull { entry -> entry.targetClassOrNull()?.let { entry to it } }
            .groupBy { (entry, _) -> entry.targetRef }
            .mapValues { (_, rows) ->
                rows.map { (entry, cls) ->
                    Candidate.vocabulary(
                        // Term + lang, the same identity rule `LexiconArchiveSource` uses: one term
                        // may legitimately point at one target in two languages.
                        id = "learned:${entry.targetRef}:${entry.lang}:${entry.term}",
                        value = entry.term,
                        targetRef = entry.targetRef,
                        source = SourceTag.LEARNED,
                        // No authored method: nobody wrote a learned alias down, which is what
                        // makes it learned. `MethodDispatcher` passes such rows through untouched.
                        matchMethod = null,
                        targetClass = cls,
                    )
                }
            }

    /**
     * NEGATIVE entries by **folded** term. Only ACTIVE ones suppress: a PROPOSED negative is one
     * refusal, which RV-P7.2 ruled is not enough to deny a term for a whole estate.
     *
     * ⚑ **Folded, not canonical.** `canonical` is the *authored* form — diacritics preserved —
     * and exists so `EXACT` dispatch compares against what an author actually wrote. This map
     * answers a different question ("did the user's span reach this term?"), which is a retrieval
     * question, and `fold` is the retrieval key: it erases diacritics precisely so `zakaznik`
     * reaches `zákazník`. Keyed on the authored form, a refusal recorded as `distribuční centrum`
     * would not apply to a user who typed it without the accents — a distinction nobody making the
     * refusal intended to draw.
     */
    private fun OverlayDoc.toSuppressions(): Map<String, Set<String>> =
        entries
            .filter { it.polarity == NEGATIVE && it.status == ACTIVE }
            .groupBy { TextNormalizer.fold(it.term) }
            .mapValues { (_, rows) -> rows.map { it.targetRef }.toSet() }

    /**
     * Null for a class this build does not know — a newer producer's addition. The row is dropped
     * with one warning per distinct value rather than defaulted: defaulting would file a learned
     * alias under a class it may not belong to, and a class-scoped lookup would then answer with it.
     */
    private fun OverlayDocEntry.targetClassOrNull(): TargetClass? =
        when (targetClass) {
            "MODEL_OBJECT" -> TargetClass.MODEL_OBJECT
            "MEMBER" -> TargetClass.MEMBER
            "OPERATOR" -> TargetClass.OPERATOR
            "GROUNDING_TRIGGER" -> TargetClass.GROUNDING_TRIGGER
            "STRING_PREDICATE" -> TargetClass.STRING_PREDICATE
            else -> {
                if (unknownClasses.add(targetClass)) {
                    logger.warn(
                        "Overlay entry '{}' → {} carries target class '{}', which this build does not know — dropped",
                        term,
                        targetRef,
                        targetClass,
                    )
                }
                null
            }
        }

    /** Warned-about classes, so an archive full of a newer class logs once rather than per row. */
    private val unknownClasses =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
}
