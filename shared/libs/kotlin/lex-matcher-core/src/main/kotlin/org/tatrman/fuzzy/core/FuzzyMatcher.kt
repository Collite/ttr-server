// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One step of an algorithm cascade: run [algorithm], treat it as the winner if its top-1 score ≥ [minScore]. */
data class CascadeStep(
    val algorithm: AlgorithmType,
    val minScore: Double,
)

/** A BatchMatch span (contracts §2): match [query] across [categories]; explicit-unknown ⇒ EMPTY slot. */
data class SpanQuery(
    val query: String,
    val categories: List<String>,
    val limit: Int = 10,
)

/** One span's result within a BatchMatch. */
data class BatchSpanResult(
    val matches: List<FuzzyMatchResult>,
    val matchedAlgorithm: AlgorithmType?,
)

/** BatchMatch outcome: positional [results] + the one [vocabularyVersion] read for the whole call. */
data class BatchMatchResult(
    val results: List<BatchSpanResult>,
    val vocabularyVersion: String,
)

/**
 * RV-P1.4 T5 — a category-scoped lookup: the request shape the resolver's **lookup rung** calls
 * (RV-33 — "deterministic, anchored, category-scoped; always eligible first"), and the contract
 * P2.3's lookup rounds are written against.
 *
 * Distinct from [SpanQuery], which is a *span* of a parsed question inside a batch. This is one
 * term, deliberately scoped, asked on its own — the rung already knows what it is looking for and
 * wants a bounded deterministic answer, not a cascade over the estate.
 */
data class LookupQuery(
    val term: String,
    /**
     * Category keys to search — the same keys [MatchRepository.getCandidates] uses, which for the
     * compiled lexicon means target refs (`md.measure.net`, `op:trend`). **Empty = every category**:
     * a deliberate cross-category lookup, the shape a rung uses when it does not yet know the
     * target. An explicit-but-unknown category contributes nothing (the per-slot leak guard) and is
     * reported back in [LookupResult.unknownCategories] rather than silently yielding zero hits.
     */
    val categories: List<String> = emptyList(),
    /**
     * Restrict to these target classes (RV-38). Empty = no class filter. Scoping by class rather
     * than by ref is what lets a rung ask "which operator is this?" without first enumerating the
     * estate's operators.
     */
    val targetClasses: Set<TargetClass> = emptySet(),
    /**
     * Replaces the **authored** method for this call — the rung's strict-first, then widen pass.
     * Applies only to rows that carry an authored method; see [MethodDispatcher.dispatch].
     */
    val methodOverride: MatchMethod? = null,
    /**
     * `<= 0` ⇒ [FuzzyMatcher.DEFAULT_LOOKUP_CANDIDATES]; anything above
     * [FuzzyMatcher.MAX_LOOKUP_CANDIDATES] is clamped to it. The rung's question is bounded by
     * construction, and an uncapped `int32` on the wire is an invitation to ask for the estate.
     */
    val maxCandidates: Int = FuzzyMatcher.DEFAULT_LOOKUP_CANDIDATES,
)

/** One lookup's answer: the surviving candidates plus what was actually searched. */
data class LookupResult(
    val candidates: List<FuzzyMatchResult>,
    /**
     * Requested categories that do not exist in the loaded vocabulary. The leak guard already
     * returns nothing for these; naming them turns "no candidates" into a diagnosable answer
     * instead of an ambiguous one — a stale ref and a genuine miss look identical otherwise.
     */
    val unknownCategories: List<String> = emptyList(),
)

/** Outcome of a cascade run: the matches returned and which algorithm produced them (null if no steps). */
data class CascadeOutcome(
    val matches: List<FuzzyMatchResult>,
    val matchedAlgorithm: AlgorithmType?,
)

/**
 * Builds the cascade for a request: the explicit [algorithms] when non-empty,
 * otherwise a single legacy-[algorithm] step that always "wins" (min-score
 * −∞) so the pre-cascade single-algorithm contract is preserved.
 */
fun cascadeFrom(
    algorithms: List<CascadeStep>,
    algorithm: String?,
): List<CascadeStep> =
    algorithms.ifEmpty {
        listOf(CascadeStep(AlgorithmType.fromString(algorithm), Double.NEGATIVE_INFINITY))
    }

class FuzzyMatcher(
    private val repository: MatchRepository,
    private val lemmatizer: Lemmatizer = NoopLemmatizer,
    // GH #69 — IDF token weighting for the TATRMAN (token-based) path. Defaulted
    // so existing call sites / tests are unaffected; wired from config in Application.
    // ⚑ A V1 switch only: v2 always weighs by IDF (contracts §4.3) and ignores it (review-103 L2 —
    // the service WARNs at startup when it is off under v2).
    private val idfEnabled: Boolean = true,
    // FZ-P2 — retrieval path. Defaulted LEGACY so existing call sites / tests / goldens are
    // byte-identical; Application wires it from `fuzzy.token-based.retrieval`. The retriever is the
    // seam FZO plugs OpenSearch into; the in-memory default resolves against the interned vocabulary.
    private val retrievalMode: RetrievalMode = RetrievalMode.LEGACY,
    // LP-P0 — the TATRMAN scorer (`fuzzy.match.version`). Defaulted V1 (byte-pinned) so existing call
    // sites / tests / goldens are unaffected; V2 needs INDEX_FIRST (checked in init). The default
    // retriever follows it, so a v2 prefix hit is retrievable, not only rescorable.
    //
    // ⚑ This default did NOT move at LP-P3. The `lex-matcher` service ships v2 from its own
    // application.conf; this is the LIBRARY, and its other embedder (ai-platform's fuzzy-matcher,
    // via `org.tatrman:lex-matcher-core`) chooses its engine in its own deployment. An embedder
    // that never passes this argument keeps the engine it was built against — which is the whole
    // meaning of "v1 is byte-pinned" in contracts §4.1.
    private val matchVersion: MatchVersion = MatchVersion.V1,
    private val retriever: CandidateRetriever = IndexFirstRetriever(matchVersion, repository::getVocabulary),
    // RV-P1.4 T4 — honours the authored match method (RV-32) on whatever the cascade produced.
    // A no-op for candidates with no authored method, so the pre-RV service is unaffected.
    private val methodDispatcher: MethodDispatcher = MethodDispatcher(),
    // review-103 F3 (ruling 2, D3) — how v2 keeps a non-all-exact row under 1.0. Travels exactly like
    // [matchVersion]: the service wires it from `fuzzy.match.v2.normalize`, an embedder that never
    // passes it gets the same default the service ships (scale, 0.99). Inert under V1.
    private val v2Normalization: V2Normalization = V2Normalization.DEFAULT,
) {
    init {
        matchVersion.requireCompatible(retrievalMode)
    }

    /** LP-P0 — the effective engine version, echoed in `FuzzyStatusResponse.engine_version`. */
    val engineVersion: String get() = matchVersion.wire

    /** D3 — the effective v2 normalization (meaningful only when [engineVersion] is `v2`). */
    val normalization: V2Normalization get() = v2Normalization

    suspend fun match(
        query: String,
        category: String?,
        algorithmType: AlgorithmType?,
        limit: Int,
    ): List<FuzzyMatchResult> {
        val scored = runSingle(query, category, algorithmType ?: AlgorithmType.TATRMAN, limit)
        return consultOverlay(repository.overlay().pinned(), query, listOfNotNull(category), scored).take(limit)
    }

    /**
     * Runs an algorithm cascade. The steps are tried in order; the first whose
     * top-1 score is ≥ its [CascadeStep.minScore] wins and its full (sorted,
     * limited) result set is returned — precision-first, recall-fallback. This
     * avoids comparing scores across algorithms (different scales): one
     * algorithm's results are returned wholesale, never merged.
     *
     * `minScore` is the cascade *decision gate* on the top candidate, NOT an
     * output filter — the winner's runner-up candidates are returned intact so
     * callers can still detect ambiguity / offer clarification.
     *
     * If no step clears its bar, the last (most recall-oriented) step's
     * best-effort results are returned, with that algorithm reported as the
     * match. Empty [steps] yields an empty outcome.
     */
    suspend fun matchCascade(
        query: String,
        category: String?,
        steps: List<CascadeStep>,
        limit: Int,
    ): CascadeOutcome {
        if (steps.isEmpty()) return CascadeOutcome(emptyList(), null)
        // Pinned ONCE for the whole cascade: a reload between two steps would let the step that
        // wins have been decided against a different overlay than the one the tuple names.
        val overlay = repository.overlay().pinned()
        var lastResults: List<FuzzyMatchResult> = emptyList()
        var lastAlgorithm: AlgorithmType? = null
        for (step in steps) {
            // The overlay is consulted per step, before the gate: a learned alias is evidence like
            // any other, and a step it satisfies should win rather than fall through to a looser
            // algorithm. Cascades return on the first qualifying step, so this is 1-2 consults.
            val scored = runSingle(query, category, step.algorithm, limit)
            val results = consultOverlay(overlay, query, listOfNotNull(category), scored).take(limit)
            lastResults = results
            lastAlgorithm = step.algorithm
            val topScore = results.firstOrNull()?.score ?: Double.NEGATIVE_INFINITY
            if (topScore >= step.minScore) {
                return CascadeOutcome(results, step.algorithm)
            }
        }
        // Option (a): no step qualified — fall back to the last step's results.
        return CascadeOutcome(lastResults, lastAlgorithm)
    }

    /**
     * RG-P2.S2 — N spans × M categories in ONE call (the resolver's gate-spans
     * step). Each span matches its [SpanQuery.query] across its categories
     * (explicit-unknown ⇒ EMPTY slot, the per-slot leak guard) and the merged
     * top-[SpanQuery.limit] are returned, positional to [spans]. Spans fan out
     * with bounded parallelism; the `vocabularyVersion` is read ONCE up front so
     * the whole batch reflects a consistent snapshot.
     */
    suspend fun batchMatch(
        spans: List<SpanQuery>,
        maxParallelism: Int = 8,
    ): BatchMatchResult {
        val version = repository.vocabularyVersion()
        if (spans.isEmpty()) return BatchMatchResult(emptyList(), version)

        // T4 — one overlay for the whole batch, read here for the same reason `version` is: a
        // reload landing between two spans would answer them from two different overlays and
        // report one version for both.
        val overlay = repository.overlay().pinned()
        val gate = Semaphore(maxParallelism.coerceAtLeast(1))
        val results =
            coroutineScope {
                spans
                    .map { span -> async { gate.withPermit { matchSpan(span, overlay) } } }
                    .awaitAll()
            }
        return BatchMatchResult(results, version)
    }

    /**
     * RV-P1.4 T5 — one term, deliberately scoped: the resolver's lookup rung (RV-33).
     *
     * Deterministic by construction — a single TATRMAN pass, no cascade. The rung's contract is
     * "anchored and category-scoped, always eligible first", so trying progressively looser
     * algorithms would be answering a question it did not ask; widening is the caller's job, via
     * [LookupQuery.methodOverride] and a second round it chose to run.
     *
     * The stage order is load-bearing, and each step is where it is for a reason:
     * score+dispatch per category → merge → **re-margin over the union** → **consult the overlay
     * (which re-margins again, now suppression-aware)** → filter by class → take the limit. The
     * first re-margin is what a merged answer needs whether or not an overlay exists; the second
     * is RV-P7.3 T3's ordering, so a suppressed target stops counting as a rival. Taking the limit
     * before any of it would manufacture uniqueness by truncation.
     */
    suspend fun lookup(query: LookupQuery): LookupResult {
        val limit =
            if (query.maxCandidates > 0) {
                query.maxCandidates.coerceAtMost(MAX_LOOKUP_CANDIDATES)
            } else {
                DEFAULT_LOOKUP_CANDIDATES
            }
        val unknown =
            repository.knownCategories()?.let { known ->
                query.categories.filter { it.lowercase() !in known }
            } ?: emptyList()

        // Empty ⇒ the deliberate cross-category lookup. An explicit-but-unknown category is left in
        // the list: it contributes nothing (the leak guard) and is reported separately.
        val targets: List<String?> = query.categories.ifEmpty { listOf(null) }
        val scored =
            targets.flatMap { category ->
                runSingle(query.term, category, AlgorithmType.TATRMAN, limit, query.methodOverride)
            }

        val merged =
            scored
                .sortedByDescending { it.score }
                .distinctBy { it.candidateId to it.category }
        // Re-asked over the union: per-category margins cannot see a rival in a sibling category,
        // and the compiled artifact keys one category per target ref, so for a declared term EVERY
        // rival is in a sibling category.
        val remargined = methodDispatcher.recomputeMargins(merged, query.methodOverride)
        // ONE consult over the whole answer, not one per category — a NEGATIVE entry is a statement
        // about a candidate, and a store that saw only one category's slice could not make it.
        val withOverlay =
            consultOverlay(
                repository.overlay().pinned(),
                query.term,
                query.categories,
                remargined,
                query.methodOverride,
            )

        val filtered =
            if (query.targetClasses.isEmpty()) {
                withOverlay
            } else {
                // Member values carry no target class, so a class-scoped lookup excludes them —
                // which is the point: "which operator is this?" must not be answered with a row of
                // data that happens to read alike. Overlay additions are held to the same bar, so a
                // store that wants its learned aliases to survive this must state their class.
                withOverlay.filter { it.targetClass in query.targetClasses }
            }

        return LookupResult(candidates = filtered.take(limit), unknownCategories = unknown)
    }

    private suspend fun matchSpan(
        span: SpanQuery,
        overlay: OverlayStore,
    ): BatchSpanResult {
        val limit = if (span.limit > 0) span.limit else DEFAULT_LOOKUP_CANDIDATES

        // Empty categories ⇒ deliberate global (cross-category) lookup; otherwise
        // match per explicit category (unknown ones contribute nothing — leak guard).
        //
        // One TATRMAN pass per category, which is what `cascadeFrom(emptyList(), TATRMAN)` always
        // produced here — a single step with no min-score. Calling [runSingle] directly is the same
        // matching, and it is what lets the overlay be consulted ONCE below over the merged answer
        // rather than once per category inside each cascade.
        val targets: List<String?> = span.categories.ifEmpty { listOf(null) }
        val perCategory = targets.map { cat -> runSingle(span.query, cat, AlgorithmType.TATRMAN, limit) }

        val matchedAlgorithm = if (perCategory.any { it.isNotEmpty() }) AlgorithmType.TATRMAN else null
        val merged =
            perCategory
                .flatten()
                .sortedByDescending { it.score }
                .distinctBy { it.candidateId to it.category }
        // Same stage order as [lookup], for the same reasons: re-margin over the union first (a span
        // asked about several categories), then ONE overlay consult over the whole answer — which
        // re-margins again once suppression is known — then the limit, so neither a rival nor a
        // NEGATIVE verdict can be lost to truncation.
        val remargined = methodDispatcher.recomputeMargins(merged)
        val withOverlay = consultOverlay(overlay, span.query, span.categories, remargined)
        return BatchSpanResult(withOverlay.take(limit), matchedAlgorithm)
    }

    /**
     * Scores one category and applies the authored-method gate. **Does not consult the overlay and
     * does not truncate** — both belong to the caller, which knows whether more narrowing follows.
     *
     * @param limit what the caller ultimately wants; scoring uses [scoringLimit] headroom on top.
     */
    private suspend fun runSingle(
        query: String,
        category: String?,
        algorithmType: AlgorithmType,
        limit: Int,
        methodOverride: MatchMethod? = null,
    ): List<FuzzyMatchResult> {
        val candidates = repository.getCandidates(category)
        // Headroom, because dispatch and the T5 class filter narrow AFTER scoring. Scoring at
        // exactly `limit` lets a candidate the author's method rejects consume a slot an admissible
        // one needed: an EXACT term ranked limit+1 by the deliberately recall-oriented folded index
        // would be truncated before the gate ever saw it, and the query would answer "nothing"
        // where the right answer existed. Same shape as the INDEX_FIRST retriever's topN headroom.
        //
        // Only where some row can actually be narrowed after scoring, though — see
        // [MatchRepository.narrowsAfterScoring]. Widening is observable (TokenBasedMatcher sizes its
        // defensive non-seed sample by the limit), so a store with nothing to narrow must not pay
        // it: that is T7's byte-identical promise, and the parity goldens hold it. Since MV a member
        // vocabulary held to EXACT or TYPOS(n) is such a row (review-104 F10).
        val fetchLimit = if (repository.narrowsAfterScoring(category)) scoringLimit(limit) else limit
        val scored =
            if (algorithmType == AlgorithmType.TATRMAN) {
                matchWithTokenBased(query, category, candidates, fetchLimit)
            } else {
                withContext(Dispatchers.Default) {
                    matchWithStandardAlgorithm(query, category, algorithmType, candidates, fetchLimit)
                }
            }
        // RV-P1.4 T4 — the authored method gates here, at the single point every entry point funnels
        // through (match / matchCascade / batchMatch / lookup), so no caller can route around it.
        // Inside the cascade rather than after it on purpose: a candidate the author's method
        // rejects must not be allowed to satisfy a cascade step's min-score and stop the fallback.
        // RV-44 — the query's lemma form, for a profile's `lemma` norm.
        //
        // ⚑ Computed ONLY when some scored row actually declares a `lemma` rule. [lemmatizer] is
        // `NlpLemmatizer` in production — one gRPC BatchLemmatize per call, uncached — and
        // [matchWithTokenBased] has already made that call for the same tokens on the TATRMAN
        // path. Computing it unconditionally here therefore doubled the NLP round-trips per
        // category per query, added one to the standard-algorithm path that never had it, and
        // charged both to estates that author no profiles at all — the cost `narrowsAfterScoring`
        // above exists to keep a store with nothing to narrow from paying. `null` ⇒ [QueryForms.of] collapses
        // the lemma axis onto the folded one, which is where it sits anyway with no lemmatiser.
        return methodDispatcher.dispatch(query, scored, methodOverride, lemmaQuery(query, scored))
    }

    /**
     * Folded lemmas of the query, joined — the shape [Candidate.lemmaValue] holds candidate-side.
     * Null when nothing in [scored] compares on the lemma stratum, which is every estate that has
     * not authored a `{ norm: lemma }` rule.
     */
    private suspend fun lemmaQuery(
        query: String,
        scored: List<FuzzyMatchResult>,
    ): String? {
        val wanted = scored.any { row -> row.matchProfile?.rules?.any { it.norm == Norm.LEMMA } == true }
        if (!wanted) return null
        val raw = Candidate.tokenizeRaw(query)
        val lemmas = lemmatizer.lemmatize(raw)
        return raw.joinToString(" ") { lemmas[it] ?: TextNormalizer.fold(it) }
    }

    /**
     * The overlay's NEGATIVE half, applied after the other layers have spoken.
     *
     * Its POSITIVE half is not here: RV-P7.3 loads learned entries into the index, so by the time
     * this runs they have already been retrieved and scored like any other candidate. What is left
     * is polarity — targets the estate has learned this term does not mean — and those are
     * **flagged, not removed** ([OverlayVerdict.suppressedTargets]): the candidate is still
     * returned, still ranked, and never auto-bound (RV-2).
     *
     * **Then the margin is recomputed**, which is the T3 ordering: suppression applies after the
     * layer merge and *before* the uniqueness margin, so a denied target stops counting as a rival
     * to the one the user actually meant. That recompute is only safe because the estate's
     * statement rides [FuzzyMatchResult.suppressed] rather than `autoBindable` alone — see
     * [MethodDispatcher.recomputeMargins].
     *
     * With the default [NoopOverlayStore] the verdict is empty and the input list is returned as-is
     * — same instances, no recompute — so a deployment with no learning history is byte-identical
     * to the pre-overlay service.
     *
     * **Does not truncate**, deliberately: [lookup] still has its class filter to run afterwards,
     * and cutting to `limit` here would throw away the very headroom [runSingle] scored to give it.
     * Every caller applies its own `take(limit)` once nothing further narrows.
     */
    private suspend fun consultOverlay(
        overlay: OverlayStore,
        term: String,
        categories: List<String>,
        results: List<FuzzyMatchResult>,
        // Carried so the recompute below asks the uniqueness question over the same set of rows the
        // caller's own re-margin did. Reading the authored method back without it would compute the
        // margin over a different population than the one that produced the answer.
        methodOverride: MatchMethod? = null,
    ): List<FuzzyMatchResult> {
        val verdict = overlay.consult(OverlayRequest(term, categories, results))
        if (verdict.isEmpty) return results

        val flagged =
            results.map {
                if (it.targetRef in verdict.suppressedTargets) {
                    it.copy(suppressed = true, autoBindable = false)
                } else {
                    it
                }
            }
        return methodDispatcher.recomputeMargins(flagged, methodOverride)
    }

    /**
     * How wide to score before the narrowing steps run. Bounded so a caller's `limit` cannot be
     * multiplied into an unbounded scan, and floored so a small `limit` still leaves room for the
     * gate to reject a few rows without emptying the answer.
     */
    private fun scoringLimit(limit: Int): Int =
        (limit.coerceAtLeast(1).coerceAtMost(MAX_SCORING_CANDIDATES / GATE_HEADROOM_FACTOR) * GATE_HEADROOM_FACTOR)
            .coerceAtLeast(MIN_SCORING_CANDIDATES)

    companion object {
        /** `max_candidates <= 0` on a [LookupQuery] / [SpanQuery]. */
        const val DEFAULT_LOOKUP_CANDIDATES: Int = 10

        /**
         * Ceiling on a caller's `max_candidates` (RV-33). The rung asks a bounded, deterministic
         * question; without a cap an `int32` field lets one request ask for the whole estate scored
         * and serialised. Callers that want more paginate by narrowing their categories, which is
         * the scoping the surface exists to provide.
         */
        const val MAX_LOOKUP_CANDIDATES: Int = 200

        /**
         * How much wider than the caller's limit to score, so the post-scoring gate and class
         * filter have rows to reject without emptying the answer. Mirrors the `topN` headroom the
         * INDEX_FIRST retriever already applies for the same reason.
         */
        const val GATE_HEADROOM_FACTOR: Int = 4

        /** Floor on the headroom, so a `limit` of 1 or 2 still scores a usable window. */
        const val MIN_SCORING_CANDIDATES: Int = 50

        /** Hard ceiling on the headroom, so `limit * factor` can never become an unbounded scan. */
        const val MAX_SCORING_CANDIDATES: Int = 2_000
    }

    private suspend fun matchWithTokenBased(
        query: String,
        category: String?,
        candidates: List<Candidate>,
        limit: Int,
    ): List<FuzzyMatchResult> {
        // Two query token axes: folded surface (handles diacritic-stripped input) and folded lemma
        // (handles inflection). Feed the lemmatiser raw (accented) tokens; fold its lemmas. With
        // NoopLemmatizer the lemma axis collapses onto the surface axis (harmless no-op).
        val rawQueryTokens = Candidate.tokenizeRaw(query)
        val querySurfaceTokens = rawQueryTokens.map { TextNormalizer.fold(it) }
        val lemmaMap = lemmatizer.lemmatize(rawQueryTokens)
        val queryLemmaTokens = rawQueryTokens.map { lemmaMap[it] ?: TextNormalizer.fold(it) }

        // Repository normalises the category key (case-insensitive lookup).
        val tokenIndex = repository.getTokenIndex(category)
        val results =
            withContext(Dispatchers.Default) {
                when (retrievalMode) {
                    RetrievalMode.LEGACY -> {
                        val matcher =
                            TokenBasedMatcher(
                                candidates = candidates,
                                tokenIndex = tokenIndex,
                                distanceCache = repository.getDistanceCache(category),
                                idfEnabled = idfEnabled,
                            )
                        matcher.match(querySurfaceTokens, queryLemmaTokens, limit).map { (c, score) ->
                            Scored(c, score)
                        }
                    }
                    RetrievalMode.INDEX_FIRST -> {
                        // Retrieve the topN candidates worth scoring against the interned vocabulary,
                        // then exact-rescore them with the selected scorer (v1 unchanged, or v2 — LP-P0).
                        // The retriever resolves
                        // each query token once against the vocabulary and returns the best-first
                        // candidates from a single snapshot (no ordinal escapes the retriever, so a
                        // concurrent refresh cannot mis-map one). The rescore uses a throwaway
                        // DistanceCache, so the shared category cache stays off this path.
                        //
                        // topN headroom: index-first legitimately reaches candidates legacy never
                        // seeds (a fuzzy-resolved token pulls in rows with no exact overlap), so the
                        // rescore set must be wide enough that legacy's true top-k are not crowded
                        // out before the exact re-rank. 500 keeps the rescore cheap (µs-per-candidate)
                        // while giving the parity-or-better gate ample margin.
                        val topN = maxOf(500, limit * 4)
                        val retrieved = retriever.retrieve(querySurfaceTokens, queryLemmaTokens, category, topN)
                        val scorer: TokenScorer =
                            when (matchVersion) {
                                MatchVersion.V1 ->
                                    TokenBasedMatcher(
                                        candidates = retrieved,
                                        tokenIndex = tokenIndex,
                                        distanceCache = DistanceCache(),
                                        idfEnabled = idfEnabled,
                                    )
                                // v2 always weighs by IDF (contracts §4.3): `idfEnabled` is a v1 switch.
                                MatchVersion.V2 -> TokenBasedMatcherV2(tokenIndex, normalization = v2Normalization)
                            }
                        scorer.score(querySurfaceTokens, queryLemmaTokens, retrieved, limit)
                    }
                }
            }

        val method = if (matchVersion == MatchVersion.V2) TokenBasedMatcherV2.METHOD else "TATRMAN"
        return results.map { scored ->
            scored.candidate.toResult(scored.score, category, method, scored.tokenHits, scored.coverage)
        }
    }

    private fun matchWithStandardAlgorithm(
        query: String,
        category: String?,
        algorithmType: AlgorithmType?,
        candidates: List<Candidate>,
        limit: Int,
    ): List<FuzzyMatchResult> {
        val algorithm = AlgorithmFactory.get(algorithmType ?: AlgorithmType.LEVENSHTEIN)
        val foldedQuery = TextNormalizer.fold(query)

        return candidates
            .map { candidate ->
                // Match on folded text so diacritic-only differences don't penalise the score,
                // but return the candidate's original value to the caller. FZ-P1 T5 — use the
                // candidate's precomputed fold instead of folding per request.
                val score = algorithm.similarity(foldedQuery, candidate.foldedValue)
                candidate.toResult(score, category, method = (algorithmType ?: AlgorithmType.LEVENSHTEIN).name)
            }.sortedByDescending { it.score }
            .take(limit)
    }
}

/**
 * Maps a scored [Candidate] to a [FuzzyMatchResult], carrying its source tag +
 * lexicon target_ref (RS-15) and stamping S-4 provenance (producer=fuzzy,
 * method=the algorithm that scored it).
 *
 * Note the two `method`s are different axes and both travel: [FuzzyMatchResult.matchMethod] is the
 * AUTHORED method from the lexicon (RV-32), while [Provenance.method] is the algorithm that
 * produced this score. Conflating them would make "the author asked for EXACT" indistinguishable
 * from "the TATRMAN cascade happened to win".
 */
private fun Candidate.toResult(
    score: Double,
    category: String?,
    method: String,
    tokenHits: List<TokenHit> = emptyList(),
    coverage: Double? = null,
): FuzzyMatchResult =
    FuzzyMatchResult(
        candidateId = id,
        candidate = value,
        score = score,
        // The category asked, else the row's own (review-104 F12): a cross-category lookup asks
        // none, and a member row still belongs to exactly one vocabulary.
        category = category ?: this.category ?: "unknown",
        source = source,
        targetRef = targetRef,
        provenance =
            Provenance(
                producer = "fuzzy",
                method = method,
                rawScore = score,
                tokenHits = tokenHits,
                coverage = coverage,
            ),
        matchMethod = matchMethod,
        targetClass = targetClass,
        // Both precomputed at load on the candidate, so the dispatcher parses and NFC-normalises
        // nothing per request. Passing them explicitly also skips the constructor defaults, which
        // would otherwise re-derive them here for every scored row on every query.
        authoredMethod = authoredMethod,
        canonicalCandidate = canonicalValue,
        // RV-44 — both precomputed at load, for the same reason as the two above.
        matchProfile = matchProfile,
        lemmaCandidate = lemmaValue,
    )
