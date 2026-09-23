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
 * `q` comes from the per-token kind table ([MatchKind]): exact 1.0 · typo `1 − 0.15·d` within the
 * [EditBudget] · prefix `max(0.80, |t|/|c|)`; a token matching several ways takes the max q. `P` is
 * v1's query coverage with the kind qualities; `C` is only the tie-break — with ε = 0.01 two
 * candidates at equal `P` differ by ≤ ε, which is under the RV-32 margin floor, so coverage orders
 * an ask and never turns an ambiguity into a silent bind (contracts §4.5).
 *
 * Weights come from the same [TokenIndex] v1 uses (identical IDF formula to [TokenVocabulary]).
 */
class TokenBasedMatcherV2(
    private val tokenIndex: TokenIndex,
    private val orderBonusMultiplier: Double = 1.05,
    private val maxOrderBonus: Double = 1.5,
) : TokenScorer {
    private val levenshtein = Levenshtein()

    override fun score(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
        limit: Int,
    ): List<Scored> =
        candidates
            .map { scoreCandidate(querySurfaceTokens, queryLemmaTokens, it) }
            .sortedByDescending { it.score }
            .take(limit)

    /** Both axes, the better one wins — with ITS provenance (v1's axis rule). */
    internal fun scoreCandidate(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidate: Candidate,
    ): Scored {
        val surface = axisScore(querySurfaceTokens, candidate.tokens, candidate)
        val lemmaIdentical = queryLemmaTokens == querySurfaceTokens && candidate.lemmaTokens == candidate.tokens
        if (lemmaIdentical) return surface
        val lemma = axisScore(queryLemmaTokens, candidate.lemmaTokens, candidate)
        return if (lemma.score > surface.score) lemma else surface
    }

    private fun axisScore(
        queryTokens: List<String>,
        candidateTokens: List<String>,
        candidate: Candidate,
    ): Scored {
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return Scored(candidate, 0.0, coverage = 0.0)

        var weightedSum = 0.0
        var weightTotal = 0.0
        val hits = ArrayList<TokenHit>(queryTokens.size)
        val matchedPositions = HashSet<Int>()
        for ((qPos, t) in queryTokens.withIndex()) {
            val best = bestMatch(t, candidateTokens)
            if (best == null) {
                weightTotal += tokenIndex.idf(t) // idf(t), or idfAbsent for a token outside the corpus
                continue
            }
            val w = tokenIndex.idf(candidateTokens[best.cPos])
            weightedSum += w * best.quality
            weightTotal += w
            matchedPositions.add(best.cPos)
            hits.add(
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

        var candidateIdf = 0.0
        var matchedIdf = 0.0
        for ((cPos, c) in candidateTokens.withIndex()) {
            val w = tokenIndex.idf(c)
            candidateIdf += w
            if (cPos in matchedPositions) matchedIdf += w
        }
        val coverage = if (candidateIdf > 0.0) matchedIdf / candidateIdf else 0.0
        val p = if (weightTotal > 0.0) weightedSum / weightTotal else 0.0
        val s = p * orderBonus(hits) + EPSILON * coverage
        return Scored(candidate, s, hits, coverage)
    }

    private class Best(
        val cPos: Int,
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
        if (exact >= 0) return Best(exact, MatchKind.EXACT, 0, 1.0)

        val budget = EditBudget.of(t.length)
        var best: Best? = null
        for ((cPos, c) in candidateTokens.withIndex()) {
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
            if (q > 0.0 && (best == null || q > best.quality)) best = Best(cPos, kind, d, q)
        }
        return best
    }

    /**
     * v1's formula — `multiplier^(in-order pairs)`, capped — over the MATCHED candidate positions
     * (contracts §4.3): a prefix or typo hit now earns order credit, which v1's `indexOf` on the
     * exact surface never gave it.
     */
    private fun orderBonus(hits: List<TokenHit>): Double {
        var correctPairs = 0
        for (i in hits.indices) {
            for (j in i + 1 until hits.size) {
                if (hits[j].candidatePos > hits[i].candidatePos) correctPairs++
            }
        }
        return Math.pow(orderBonusMultiplier, correctPairs.toDouble()).coerceAtMost(maxOrderBonus)
    }

    companion object {
        /** The coverage tie-break weight (contracts §4.3). Must stay below the RV-32 margin floor. */
        const val EPSILON = 0.01

        /** [Provenance.method] for a v2-scored row (contracts §4.6). */
        const val METHOD = "TATRMAN_V2"
    }
}
