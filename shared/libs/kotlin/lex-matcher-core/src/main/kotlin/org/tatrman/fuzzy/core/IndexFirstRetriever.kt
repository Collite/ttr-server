// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * FZ-P2 — the in-memory index-first retriever, mirroring the validated Python restructure in the
 * 2026-07-21 performance review.
 *
 * Term-at-a-time: each query token is resolved ONCE against the distinct token vocabulary
 * ([VocabularyResolver]); each resolved vocabulary token's postings are swept, keeping the best
 * match quality per candidate per query slot. The approximate score is the same IDF-weighted
 * aggregation the exact scorer uses, with an **unmatched-token penalty** (a query token that a
 * candidate doesn't match contributes its weight to the denominator with quality 0) — this penalty
 * is what makes the approximate ordering track the exact scorer, so the true top-k land inside the
 * returned [topN] for the caller to rescore exactly.
 *
 * Cost scales with postings length, not corpus size: a typo now resolves once against the vocabulary
 * instead of once per candidate, so the all-typo worst case flips from "score everything" to cheap.
 *
 * @param vocabularyFor supplies the [TokenVocabulary] for a category (an empty vocabulary — the
 *   explicit-unknown case — yields no candidates). Wired to `StringRepository::getVocabulary`.
 * @param matchVersion LP-P0 — [MatchVersion.V2] resolves query tokens by kind
 *   ([VocabularyResolver.resolveV2]: prefix hits retrieve, qualities follow the kind table) so
 *   retrieval ranks by the same qualities the v2 scorer uses; [MatchVersion.V1] is untouched.
 */
class IndexFirstRetriever(
    private val matchVersion: MatchVersion,
    private val vocabularyFor: (String?) -> TokenVocabulary,
) : CandidateRetriever {
    /** The v1 retriever — `vocabularyFor` stays last so `IndexFirstRetriever { … }` keeps compiling. */
    constructor(vocabularyFor: (String?) -> TokenVocabulary) : this(MatchVersion.V1, vocabularyFor)

    override fun retrieve(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        category: String?,
        topN: Int,
    ): List<Candidate> {
        // Fetch the vocabulary snapshot ONCE. All ordinals below index into `vocab.candidates`, and
        // they are dereferenced against this same instance before returning — never a re-fetched one
        // — so a concurrent refresh (which swaps the vocabulary + its candidate list atomically)
        // cannot make an ordinal point at the wrong candidate or run off the end of the list.
        val vocab = vocabularyFor(category)
        if (vocab.size == 0 || topN <= 0) return emptyList()
        val resolver = VocabularyResolver(vocab)

        val surfaceScores = accumulate(querySurfaceTokens, vocab, resolver)
        val combined =
            if (queryLemmaTokens == querySurfaceTokens) {
                // No lemmatiser (or identical axes) — one accumulation suffices (mirrors scoreCandidate).
                surfaceScores
            } else {
                val lemmaScores = accumulate(queryLemmaTokens, vocab, resolver)
                maxMerge(surfaceScores, lemmaScores)
            }
        if (combined.isEmpty()) return emptyList()

        return topOrdinals(combined, topN).map { vocab.candidates[it] }
    }

    /**
     * Approximate score per touched candidate ordinal for one query-token axis. Returns ordinal →
     * score in `[0,1]`. A candidate not touched at all is simply absent (score 0, never in top-k).
     */
    private fun accumulate(
        queryTokens: List<String>,
        vocab: TokenVocabulary,
        resolver: VocabularyResolver,
    ): HashMap<Int, Double> {
        val n = queryTokens.size
        if (n == 0) return HashMap()

        val resolvedPerSlot =
            when (matchVersion) {
                MatchVersion.V1 -> Array(n) { resolver.resolve(queryTokens[it]) }
                MatchVersion.V2 -> Array(n) { resolver.resolveV2(queryTokens[it]) }
            }

        // Penalty weight for an UNMATCHED query slot: the query token's own IDF (idfAbsent if it is
        // not even in the vocabulary). This is the review's fix that makes the approx track the exact.
        val penaltyWeight =
            DoubleArray(n) { i ->
                val id = vocab.idOf(queryTokens[i])
                if (id >= 0) vocab.idf(id) else vocab.idfAbsent
            }

        // Best weight achievable at each distinct quality per slot (the accumulator stores only the
        // best quality per candidate; we reconstruct its weight from this — the review's best_w).
        val bestWeightByQuality =
            Array(n) { i ->
                val m = HashMap<Double, Double>()
                for (rt in resolvedPerSlot[i]) {
                    val cur = m[rt.quality]
                    if (cur == null || rt.weight > cur) m[rt.quality] = rt.weight
                }
                m
            }

        // Sweep postings: best match quality per candidate per query slot.
        val bestQuality = HashMap<Int, DoubleArray>()
        for (i in 0 until n) {
            for (rt in resolvedPerSlot[i]) {
                if (rt.quality <= 0.0) continue
                for (ordinal in vocab.postings(rt.tokenId)) {
                    val arr = bestQuality.getOrPut(ordinal) { DoubleArray(n) }
                    if (rt.quality > arr[i]) arr[i] = rt.quality
                }
            }
        }

        val scores = HashMap<Int, Double>(bestQuality.size * 2)
        for ((ordinal, quals) in bestQuality) {
            var weightedSum = 0.0
            var weightTotal = 0.0
            for (i in 0 until n) {
                val q = quals[i]
                if (q > 0.0) {
                    val w = bestWeightByQuality[i][q] ?: penaltyWeight[i]
                    weightedSum += w * q
                    weightTotal += w
                } else {
                    weightTotal += penaltyWeight[i]
                }
            }
            if (weightTotal > 0.0) scores[ordinal] = weightedSum / weightTotal
        }
        return scores
    }

    /** Per-candidate max of two axes' approximate scores (union of ordinals). */
    private fun maxMerge(
        a: HashMap<Int, Double>,
        b: HashMap<Int, Double>,
    ): HashMap<Int, Double> {
        val out = HashMap<Int, Double>(a)
        for ((ord, score) in b) {
            val cur = out[ord]
            if (cur == null || score > cur) out[ord] = score
        }
        return out
    }

    /** Top-[topN] ordinals by score descending, ties broken by ascending ordinal (determinism). */
    private fun topOrdinals(
        scores: HashMap<Int, Double>,
        topN: Int,
    ): IntArray {
        val ordinals = scores.keys.toIntArray()
        // Sort by score desc, then ordinal asc.
        val boxed = ordinals.toTypedArray()
        boxed.sortWith(compareByDescending<Int> { scores.getValue(it) }.thenBy { it })
        val take = minOf(topN, boxed.size)
        return IntArray(take) { boxed[it] }
    }
}
