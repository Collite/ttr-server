// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import info.debatty.java.stringsimilarity.Levenshtein
import org.slf4j.LoggerFactory

class TokenBasedMatcher(
    val candidates: List<Candidate> = emptyList(),
    val tokenIndex: TokenIndex? = null,
    val distanceCache: DistanceCache = DistanceCache(),
    private val distanceThreshold: Double = 0.20,
    private val orderBonusMultiplier: Double = 1.05,
    private val maxOrderBonus: Double = 1.5,
    // GH #69 — weight each token match by the rarity (IDF) of the matched
    // candidate token. Requires a corpus (tokenIndex); the corpus-less
    // similarity(s1,s2) path always uses the legacy char-overlap score. Flag
    // off ⇒ exact legacy behaviour, for rollback / A-B of THIS (v1) scorer only:
    // `fuzzy.match:v2` always weighs by IDF and has no such flag (review-103 L2).
    private val idfEnabled: Boolean = true,
) : TokenScorer {
    private val logger = LoggerFactory.getLogger(TokenBasedMatcher::class.java)
    private val levenshtein = Levenshtein()

    /**
     * FZ-P1 T6 — Levenshtein with an early-exit cutoff at `max(len)`. The true distance never
     * exceeds `max(len1, len2)`, so this cutoff never truncates a result — it is score-invariant —
     * while letting debatty's bounded implementation abandon rows that already exceed the cap.
     */
    private fun boundedDistance(
        a: String,
        b: String,
    ): Double = levenshtein.distance(a, b, maxOf(a.length, b.length))

    fun match(
        query: String,
        limit: Int,
    ): List<Pair<Candidate, Double>> {
        val tokens = Candidate.tokenize(query)
        return match(tokens, tokens, limit)
    }

    /** Convenience overload — query surface tokens only (lemma axis == surface axis). */
    fun match(
        queryTokens: List<String>,
        limit: Int,
    ): List<Pair<Candidate, Double>> = match(queryTokens, queryTokens, limit)

    /**
     * Match a query against the candidate set on two axes — folded-surface tokens and folded
     * lemma tokens — keeping the better score per candidate. Callers that lemmatise the query
     * (Phase 02 Stage B) pass the lemma tokens as [queryLemmaTokens]; when lemmatisation is off,
     * both lists are the same and behaviour is identical to the surface-only matcher.
     */
    fun match(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        limit: Int,
    ): List<Pair<Candidate, Double>> {
        if (querySurfaceTokens.isEmpty() && queryLemmaTokens.isEmpty()) {
            return emptyList()
        }

        logger.debug("Matching query tokens: surface=$querySurfaceTokens lemma=$queryLemmaTokens")

        val index = tokenIndex
        val seedTokens = (querySurfaceTokens + queryLemmaTokens).distinct()
        val seedIds = index?.findCandidatesWithAnyToken(seedTokens) ?: emptySet()

        if (seedIds.isEmpty()) {
            // No candidate shares an exact token with the query (e.g. every token is a typo) — fuzzy-score all.
            return scoreAll(querySurfaceTokens, queryLemmaTokens, candidates, limit)
        }

        val seedCandidates = seedIds.mapNotNull { index?.getCandidateById(it) }
        // Defensively also fuzzy-score a few candidates with no token overlap, in case one is a closer fuzzy match.
        // FZ-P1 T4 — lazy: take only `limit` non-seed candidates instead of eagerly allocating a
        // near-full copy of the candidate list per request (byte-identical: same first `limit`
        // non-seed candidates in list order).
        val extras =
            candidates
                .asSequence()
                .filter { it.id !in seedIds }
                .take(limit)
                .toList()

        // FZ-P1 T7 — select the top-`limit` with a SINGLE stable sort over seeds ++ extras. The
        // legacy path stable-sorted the seeds, concatenated the extras, then stable-sorted again;
        // for a stable sort keyed on score, sort(sort(seeds) ++ extras) == sort(seeds ++ extras),
        // so dropping the redundant seed pre-sort is byte-identical (ties keep seed-iteration order)
        // while removing one ~seed-sized sort per request.
        val scored = ArrayList<Pair<Candidate, Double>>(seedCandidates.size + extras.size)
        for (candidate in seedCandidates) {
            scored.add(candidate to scoreCandidate(querySurfaceTokens, queryLemmaTokens, candidate))
        }
        for (candidate in extras) {
            scored.add(candidate to scoreCandidate(querySurfaceTokens, queryLemmaTokens, candidate))
        }
        return scored.sortedByDescending { it.second }.take(limit)
    }

    /** Score a candidate on both axes (surface and lemma) and keep the better. */
    private fun scoreCandidate(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidate: Candidate,
    ): Double {
        val surfaceScore = axisScore(querySurfaceTokens, candidate.tokens, candidate.tokenSet)
        // Skip the lemma axis when it's identical to the surface axis (no lemmatiser) — cheap shortcut.
        val lemmaIdentical = queryLemmaTokens == querySurfaceTokens && candidate.lemmaTokens == candidate.tokens
        val lemmaScore =
            if (lemmaIdentical) 0.0 else axisScore(queryLemmaTokens, candidate.lemmaTokens, candidate.lemmaTokenSet)
        return maxOf(surfaceScore, lemmaScore)
    }

    /**
     * Score one axis (surface OR lemma) of a query against a candidate. Uses the
     * IDF-weighted aggregation when enabled and a corpus is available; otherwise
     * the legacy char-overlap score. Empty token lists score 0. [candidateTokenSet]
     * is the axis's token set, used for the O(1) exact-hit short-circuit (FZ-P1 T2).
     */
    private fun axisScore(
        queryTokens: List<String>,
        candidateTokens: List<String>,
        candidateTokenSet: Set<String>,
    ): Double {
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
        return if (idfEnabled && tokenIndex != null) {
            idfWeightedScore(queryTokens, candidateTokens, candidateTokenSet)
        } else {
            calculateScore(
                queryTokens,
                candidateTokens,
                calculateSetDistance(queryTokens, candidateTokens, candidateTokenSet),
            )
        }
    }

    /**
     * GH #69 — IDF-weighted token score. Each query token is matched to its
     * nearest candidate token (Levenshtein); the per-token match quality
     * (1 − dist/maxLen, ∈ [0,1]) is weighted by the IDF of the *matched
     * candidate token* and averaged. Matching a token shared by many candidates
     * (low IDF) barely moves the score; matching a near-unique one (high IDF)
     * dominates it. The order bonus stays a multiplier; result is capped at 1.0.
     */
    private fun idfWeightedScore(
        queryTokens: List<String>,
        candidateTokens: List<String>,
        candidateTokenSet: Set<String>,
    ): Double {
        var weightedSum = 0.0
        var weightTotal = 0.0
        for (queryToken in queryTokens) {
            var bestDistance: Double
            var bestToken: String?
            // FZ-P1 T2 — exact-token short-circuit. When the query token is present in the
            // candidate's token set the nearest token is itself (distance 0, quality 1.0) and its IDF
            // weight is idf(queryToken); the legacy loop reaches the identical (bestToken=queryToken,
            // bestDistance=0) state, so this is byte-identical while skipping the Levenshtein scan.
            if (queryToken in candidateTokenSet) {
                bestDistance = 0.0
                bestToken = queryToken
            } else {
                bestDistance = Double.MAX_VALUE
                bestToken = null
                for (candidateToken in candidateTokens) {
                    val distance =
                        distanceCache.getOrCompute(queryToken, candidateToken) {
                            boundedDistance(queryToken, candidateToken)
                        }
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestToken = candidateToken
                    }
                    if (distance == 0.0) break
                }
            }
            val matched = bestToken ?: continue
            val maxLen = maxOf(queryToken.length, matched.length).coerceAtLeast(1).toDouble()
            val matchQuality = (1.0 - bestDistance / maxLen).coerceIn(0.0, 1.0)
            val weight = tokenIndex?.idf(matched) ?: 1.0
            weightedSum += weight * matchQuality
            weightTotal += weight
        }
        if (weightTotal == 0.0) return 0.0
        val baseScore = weightedSum / weightTotal
        // Order bonus stays a multiplier, matching the legacy score's range
        // (an ordered exact match reaches baseScore·orderBonus > 1.0; callers
        // already treat ≥1.0 as exact). Not capped here for that parity.
        val orderBonus = calculateOrderBonus(queryTokens, candidateTokens)
        return baseScore * orderBonus
    }

    /**
     * FZ-P2 — exact-rescore an explicit candidate list (the index-first rescore step) with the
     * UNCHANGED per-candidate scorer (surface+lemma axes, IDF aggregation, order bonus), returning
     * the top [limit] by score (stable descending sort, ties keep input order). For the candidates a
     * given query would also reach on the legacy path, this yields byte-identical scores — which is
     * what makes `index-first` parity-or-better rather than different.
     */
    fun rescore(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
        limit: Int,
    ): List<Pair<Candidate, Double>> = scoreAndSort(querySurfaceTokens, queryLemmaTokens, candidates).take(limit)

    /** LP-P0·S2 T1 — the [TokenScorer] seam as `fuzzy.match:v1`: [rescore], unchanged, no provenance. */
    override fun score(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
        limit: Int,
    ): List<Scored> = rescore(querySurfaceTokens, queryLemmaTokens, candidates, limit).map { (c, s) -> Scored(c, s) }

    private fun scoreAndSort(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
    ): List<Pair<Candidate, Double>> =
        candidates
            .map { it to scoreCandidate(querySurfaceTokens, queryLemmaTokens, it) }
            .sortedByDescending { it.second }

    private fun scoreAll(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
        limit: Int,
    ): List<Pair<Candidate, Double>> = scoreAndSort(querySurfaceTokens, queryLemmaTokens, candidates).take(limit)

    fun calculateSimilarity(
        s1: String,
        s2: String,
    ): Double {
        val tokens1 = Candidate.tokenize(s1)
        val tokens2 = Candidate.tokenize(s2)
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        val distance = calculateSetDistance(tokens1, tokens2, tokens2.toSet())
        return calculateScore(tokens1, tokens2, distance)
    }

    private fun calculateSetDistance(
        queryTokens: List<String>,
        candidateTokens: List<String>,
        candidateTokenSet: Set<String>,
    ): Double {
        var totalDistance = 0.0

        for (queryToken in queryTokens) {
            var bestDistance = Double.MAX_VALUE

            // FZ-P1 T2 — exact-token short-circuit (byte-identical: the loop would find distance 0
            // and break with bestDistance 0.0 anyway).
            if (queryToken in candidateTokenSet) {
                totalDistance += 0.0
                continue
            }

            for (candidateToken in candidateTokens) {
                val distance =
                    distanceCache.getOrCompute(queryToken, candidateToken) {
                        boundedDistance(queryToken, candidateToken)
                    }

                if (distance == 0.0) {
                    bestDistance = 0.0
                    break
                }

                if (distance < bestDistance) {
                    bestDistance = distance
                }
            }

            if (bestDistance == Double.MAX_VALUE) {
                bestDistance = queryToken.length.toDouble()
            }

            totalDistance += bestDistance
        }

        return totalDistance
    }

    private fun calculateScore(
        queryTokens: List<String>,
        candidateTokens: List<String>,
        distance: Double,
    ): Double {
        val totalChars = queryTokens.sumOf { it.length } + candidateTokens.sumOf { it.length }
        if (totalChars == 0) return 1.0

        val maxPossibleDistance = totalChars.toDouble()
        val baseScore = 1.0 - (distance / maxPossibleDistance)

        val orderBonus = calculateOrderBonus(queryTokens, candidateTokens)

        return baseScore * orderBonus
    }

    private fun calculateOrderBonus(
        queryTokens: List<String>,
        candidateTokens: List<String>,
    ): Double {
        var correctPairs = 0

        // FZ-P1 — count in-order query-token pairs allocation-free. The legacy path built a
        // HashMap<token,firstPos> per candidate (a HashMap + entry nodes for EVERY scored candidate);
        // `List.indexOf` returns the SAME first position, so this is byte-identical while removing
        // the dominant per-seed allocation. Tokens are already folded by `tokenize`, so the old
        // per-call `.lowercase()` copies were pure waste too.
        for (i in queryTokens.indices) {
            val ci = candidateTokens.indexOf(queryTokens[i])
            if (ci < 0) continue
            for (j in i + 1 until queryTokens.size) {
                val cj = candidateTokens.indexOf(queryTokens[j])
                if (cj > ci) {
                    correctPairs++
                }
            }
        }

        val bonus = Math.pow(orderBonusMultiplier, correctPairs.toDouble())
        return bonus.coerceAtMost(maxOrderBonus)
    }
}

class TokenBasedAlgorithm : MatchingAlgorithm {
    private var matcher: TokenBasedMatcher = TokenBasedMatcher()

    fun setCandidates(
        candidates: List<Candidate>,
        tokenIndex: TokenIndex,
        distanceCache: DistanceCache,
    ) {
        matcher = TokenBasedMatcher(candidates, tokenIndex, distanceCache)
    }

    fun updateConfig(
        distanceThreshold: Double,
        orderBonusMultiplier: Double,
        maxOrderBonus: Double,
    ) {
        matcher =
            TokenBasedMatcher(
                matcher.candidates,
                matcher.tokenIndex,
                matcher.distanceCache,
                distanceThreshold,
                orderBonusMultiplier,
                maxOrderBonus,
            )
    }

    override fun distance(
        s1: String,
        s2: String,
    ): Double {
        val score = similarity(s1, s2)
        val maxLen = maxOf(s1.length, s2.length).toDouble()
        return (1.0 - score) * maxLen
    }

    override fun similarity(
        s1: String,
        s2: String,
    ): Double = matcher.calculateSimilarity(s1, s2)

    companion object {
        private val levenshtein = Levenshtein()
    }
}
