// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import info.debatty.java.stringsimilarity.Levenshtein

/**
 * LP-P0 — `fuzzy.match:v2` (contracts §4.2–4.3). Scores a candidate on each query axis as
 *
 * ```
 * P = Σ wᵢqᵢ / Σ wᵢ      over QUERY tokens; w = idf(matched c), idf(t) (or idfAbsent) when unmatched
 * C = Σ idf(matched c) / Σ idf(c ∈ candidate)   — each candidate position counted once
 * S = P · orderBonus(matched candidate positions) + ε·C
 * ```
 *
 * `orderBonus` is v1's formula — `multiplier^(in-order pairs)`, capped — counted over the MATCHED
 * candidate positions in query order (contracts §4.3), so a prefix or typo hit earns order credit,
 * which v1's `indexOf` on the exact surface never gave it.
 *
 * `q` comes from the per-token kind table ([MatchKind]), on [EdgeTrim]med tokens (✅LP-7): exact 1.0
 * · typo `1 − 0.15·d` within the [EditBudget] · prefix `max(0.86, |t|/|c|)` (✅LP-8); a token
 * matching several ways takes the max q. `P` is
 * v1's query coverage with the kind qualities; `C` is only the tie-break — with ε = 0.01 two
 * candidates at equal `P` differ by ≤ ε, which is under the RV-32 margin floor, so coverage orders
 * an ask and never turns an ambiguity into a silent bind (contracts §4.5).
 *
 * Weights come from the same [TokenIndex] v1 uses (same IDF formula), with document frequency pooled over
 * the edge-trimmed form ([TokenIndex.idfV2], ✅LP-7).
 */
class TokenBasedMatcherV2(
    private val tokenIndex: TokenIndex,
    private val orderBonusMultiplier: Double = 1.05,
    private val maxOrderBonus: Double = 1.5,
) : TokenScorer {
    private val levenshtein = Levenshtein()

    /**
     * Per-instance (= per-request) memo of the pair verdict `(t, c) → kind/q`, or [NO_MATCH]. The
     * rescore set shares candidate tokens heavily (legal forms, brand words), so without it v2 ran
     * the same bounded Levenshtein once per CANDIDATE where v1's throwaway [DistanceCache] runs it
     * once per request — measurably slower on all-typo queries. Construct one scorer per request.
     */
    private val pairMemo = HashMap<String, HashMap<String, PairMatch>>()

    override fun score(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
        limit: Int,
    ): List<Scored> {
        // Two passes, one arithmetic: rank everything WITHOUT provenance (no per-candidate hit
        // lists — the rescore set is up to 500 rows and only `limit` are returned), then rebuild the
        // survivors WITH it. Both passes run the same [axisScore], so the scores are identical by
        // construction; the stable sort keeps v1's tie order (input order).
        val scores =
            DoubleArray(candidates.size) {
                scoreCandidate(querySurfaceTokens, queryLemmaTokens, candidates[it], collect = false).score
            }
        return topK(scores, limit).map { scoreCandidate(querySurfaceTokens, queryLemmaTokens, candidates[it]) }
    }

    /**
     * Indices of the [k] best [scores], best first, ties by ascending index — exactly
     * `stable sortedByDescending + take(k)`, without sorting all n. It matters for v2 in a way it
     * never did for v1: coverage makes the rescore set's scores DISTINCT, where v1's one-token
     * queries tie at 1.0 across the board and TimSort finishes an all-equal run in one linear pass.
     */
    private fun topK(
        scores: DoubleArray,
        k: Int,
    ): IntArray {
        val size = minOf(k, scores.size)
        if (size <= 0) return IntArray(0)
        val best = IntArray(size)
        var filled = 0
        for (i in scores.indices) {
            val s = scores[i]
            // Strictly-greater only: an equal score arriving later ranks after (stable order).
            if (filled == size && s <= scores[best[size - 1]]) continue
            var pos = if (filled < size) filled++ else size - 1
            while (pos > 0 && s > scores[best[pos - 1]]) {
                best[pos] = best[pos - 1]
                pos--
            }
            best[pos] = i
        }
        return best
    }

    /** Both axes, the better one wins — with ITS provenance (v1's axis rule). */
    internal fun scoreCandidate(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidate: Candidate,
        collect: Boolean = true,
    ): Scored {
        val surface = axisScore(querySurfaceTokens, candidate.tokens, candidate, lemma = false, collect)
        val lemmaIdentical = queryLemmaTokens == querySurfaceTokens && candidate.lemmaTokens == candidate.tokens
        if (lemmaIdentical) return surface
        val lemma = axisScore(queryLemmaTokens, candidate.lemmaTokens, candidate, lemma = true, collect)
        return if (lemma.score > surface.score) lemma else surface
    }

    /** One axis. [collect] = false skips the provenance (hits list) — the score is unaffected. */
    private fun axisScore(
        queryTokens: List<String>,
        candidateTokens: List<String>,
        candidate: Candidate,
        lemma: Boolean,
        collect: Boolean,
    ): Scored {
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return Scored(candidate, 0.0, coverage = 0.0)

        var weightedSum = 0.0
        var weightTotal = 0.0
        val hits = if (collect) ArrayList<TokenHit>(queryTokens.size) else null
        // Matched candidate positions: a bitmask for the (usual) ≤ 64-token candidate, a set beyond.
        var matchedMask = 0L
        val matchedSet = if (candidateTokens.size > Long.SIZE_BITS) HashSet<Int>() else null
        var matchedIdf = 0.0
        var pairs = 0
        var lastPositions: IntArray? = null
        var matchedCount = 0
        for ((qPos, t) in queryTokens.withIndex()) {
            val best = bestMatch(t, candidateTokens)
            if (best == null) {
                weightTotal += tokenIndex.idfV2(t) // idf(t), or idfAbsent for a token outside the corpus
                continue
            }
            val w = tokenIndex.idfV2(candidateTokens[best.cPos])
            weightedSum += w * best.quality
            weightTotal += w
            // Each candidate position counts once in C, however many query tokens hit it.
            val fresh =
                if (matchedSet != null) {
                    matchedSet.add(best.cPos)
                } else {
                    val bit = 1L shl best.cPos
                    (matchedMask and bit == 0L).also { matchedMask = matchedMask or bit }
                }
            if (fresh) matchedIdf += w
            // Order bonus: in-order pairs over the matched positions, in query order (v1's rule on cPos).
            val positions = lastPositions ?: IntArray(queryTokens.size).also { lastPositions = it }
            for (k in 0 until matchedCount) if (best.cPos > positions[k]) pairs++
            positions[matchedCount++] = best.cPos
            hits?.add(
                TokenHit(
                    queryToken = t,
                    candidateToken = candidateTokens[best.cPos],
                    kind = best.kind.name.lowercase(),
                    distance = best.distance,
                    queryPos = qPos,
                    candidatePos = best.cPos,
                ),
            )
        }

        val candidateIdf = tokenIndex.idfTotal(candidate, lemma)
        val coverage = if (candidateIdf > 0.0) matchedIdf / candidateIdf else 0.0
        val p = if (weightTotal > 0.0) weightedSum / weightTotal else 0.0
        val order = Math.pow(orderBonusMultiplier, pairs.toDouble()).coerceAtMost(maxOrderBonus)
        val s = p * order + EPSILON * coverage
        return Scored(candidate, s, hits.orEmpty(), coverage)
    }

    private class Best(
        val cPos: Int,
        val kind: MatchKind,
        val distance: Int,
        val quality: Double,
    )

    /** The kind-table verdict for one (query token, candidate token) pair; `quality == 0` ⇒ no match. */
    private class PairMatch(
        val kind: MatchKind,
        val distance: Int,
        val quality: Double,
    )

    /**
     * The best candidate token for query token [t] by the kind table; the earliest position wins a
     * tie (v1's `indexOf` rule). An exact hit short-circuits.
     */
    private fun bestMatch(
        t: String,
        candidateTokens: List<String>,
    ): Best? {
        val exact = candidateTokens.indexOf(t)
        if (exact >= 0) {
            // An earlier token may be exact too after the edge trim (`oil,` before `oil`): earliest wins.
            val trimmed = EdgeTrim.of(t)
            val first = (0 until exact).firstOrNull { EdgeTrim.of(candidateTokens[it]) == trimmed } ?: exact
            return Best(first, MatchKind.EXACT, 0, 1.0)
        }

        val memo = pairMemo.getOrPut(t) { HashMap() }
        var best: Best? = null
        for ((cPos, c) in candidateTokens.withIndex()) {
            val m = memo.getOrPut(c) { pairMatch(t, c) }
            if (m.quality > 0.0 && (best == null || m.quality > best.quality)) {
                best = Best(cPos, m.kind, m.distance, m.quality)
            }
        }
        return best
    }

    /**
     * The contracts §4.2 kind table for one pair, on [EdgeTrim]med forms (✅LP-7): exact on equality
     * after the trim, else typo within budget / prefix, max q.
     */
    private fun pairMatch(
        rawT: String,
        rawC: String,
    ): PairMatch {
        val t = EdgeTrim.of(rawT)
        val c = EdgeTrim.of(rawC)
        if (t == c) return EXACT_MATCH
        val budget = EditBudget.of(t.length)
        var q = 0.0
        var kind = MatchKind.TYPO
        var d = 0
        if (budget > 0 && kotlin.math.abs(c.length - t.length) <= budget) {
            val ed = levenshtein.distance(t, c, budget + 1).toInt()
            if (ed in 1..budget) {
                q = 1.0 - VocabularyResolver.TYPO_STEP * ed
                d = ed
            }
        }
        if (t.length >= VocabularyResolver.MIN_PREFIX_LENGTH && t.length < c.length && c.startsWith(t)) {
            val qp = maxOf(VocabularyResolver.PREFIX_FLOOR, t.length.toDouble() / c.length)
            if (qp > q) {
                q = qp
                kind = MatchKind.PREFIX
                d = c.length - t.length
            }
        }
        return if (q > 0.0) PairMatch(kind, d, q) else NO_MATCH
    }

    companion object {
        /** The coverage tie-break weight (contracts §4.3). Must stay below the RV-32 margin floor. */
        const val EPSILON = 0.01

        private val NO_MATCH = PairMatch(MatchKind.TYPO, 0, 0.0)
        private val EXACT_MATCH = PairMatch(MatchKind.EXACT, 0, 1.0)

        /** [Provenance.method] for a v2-scored row (contracts §4.6). */
        const val METHOD = "TATRMAN_V2"
    }
}
