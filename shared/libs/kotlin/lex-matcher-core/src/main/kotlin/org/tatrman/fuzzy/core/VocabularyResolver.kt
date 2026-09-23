// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import info.debatty.java.stringsimilarity.Levenshtein

/**
 * A query token resolved to a vocabulary token: its [tokenId], the edit [distance], the per-token
 * match [quality], and the IDF [weight] of the matched vocabulary token.
 *
 * [quality] is `1 − d/maxLen` on the v1 path ([VocabularyResolver.resolve]) and the contracts §4.2
 * kind table on the v2 path ([VocabularyResolver.resolveV2]). [kind] defaults from [distance] so v1
 * construction sites compile — and score — unchanged; a v2 prefix hit sets it explicitly (its
 * distance is the edit distance to the whole token, which is not what earned the match).
 */
data class ResolvedToken(
    val tokenId: Int,
    val distance: Int,
    val quality: Double,
    val weight: Double,
    val kind: MatchKind = if (distance == 0) MatchKind.EXACT else MatchKind.TYPO,
)

/**
 * FZ-P2 — resolves a query token ONCE against the distinct token [vocabulary] (100–1000× smaller
 * than the candidate list), replacing the per-candidate Levenshtein loop. An exact hit short-circuits;
 * otherwise it scans only the length-adjacent buckets (a token within ED 2 differs in length by ≤ 2)
 * with a cutoff-bounded Levenshtein.
 *
 * Memoisation is per-instance only (construct one per query): a query rarely repeats a token, and
 * there is deliberately NO cross-request cache — this is what retires [DistanceCache] on the
 * retrieval path.
 */
class VocabularyResolver(
    private val vocabulary: TokenVocabulary,
) {
    private val levenshtein = Levenshtein()
    private val memo = HashMap<String, List<ResolvedToken>>()
    private val memoV2 = HashMap<String, List<ResolvedToken>>()

    /**
     * The token lengths the last uncached [resolveV2] scanned for typos — structural evidence that
     * the neighbourhood is bounded by [EditBudget] (LP-P0·S1 T5). Empty after an exact hit.
     */
    internal var lastTypoScan: IntRange = IntRange.EMPTY
        private set

    /**
     * Vocabulary tokens within edit distance [MAX_EDIT_DISTANCE] of [queryToken] (must be folded),
     * sorted by (distance, tokenId) for determinism. An exact hit returns a single distance-0 entry.
     * Empty when nothing is close enough.
     */
    fun resolve(queryToken: String): List<ResolvedToken> = memo.getOrPut(queryToken) { resolveUncached(queryToken) }

    /**
     * LP-P0 (`fuzzy.match:v2`, contracts §4.2) — [queryToken] (folded) against the vocabulary by
     * kind: an exact hit is the single entry (q 1.0); otherwise typo hits within
     * [EditBudget.of]`(len t)` edits (q `1 − 0.15·d`) plus prefix hits from a range scan of the
     * sorted vocabulary (`len t ≥ 3`, `len t < len c`, q `max(0.80, len t / len c)`). One entry per
     * vocabulary token, carrying the kind with the higher q (typo on a tie — the table's preference
     * order). Sorted by (−q, tokenId).
     */
    fun resolveV2(queryToken: String): List<ResolvedToken> =
        memoV2.getOrPut(queryToken) { resolveV2Uncached(queryToken) }

    private fun resolveUncached(queryToken: String): List<ResolvedToken> {
        val exact = vocabulary.idOf(queryToken)
        if (exact >= 0) {
            return listOf(ResolvedToken(exact, distance = 0, quality = 1.0, weight = vocabulary.idf(exact)))
        }

        val qLen = queryToken.length
        val out = ArrayList<ResolvedToken>()
        for (len in (qLen - MAX_EDIT_DISTANCE)..(qLen + MAX_EDIT_DISTANCE)) {
            val bucket = vocabulary.lengthBuckets[len] ?: continue
            for (id in bucket) {
                val vocabToken = vocabulary.tokens[id]
                // Cutoff at MAX_EDIT_DISTANCE + 1: debatty early-exits returning the limit once a row
                // minimum reaches it, so a return value of MAX+1 means "distance ≥ MAX+1" (a miss),
                // while 0..MAX are exact. This is the only reliable way to tell an ED-2 hit from ED-3.
                val d = levenshtein.distance(queryToken, vocabToken, MAX_EDIT_DISTANCE + 1).toInt()
                if (d > MAX_EDIT_DISTANCE) continue
                val maxLen = maxOf(qLen, vocabToken.length).coerceAtLeast(1)
                val quality = (1.0 - d.toDouble() / maxLen).coerceIn(0.0, 1.0)
                out.add(ResolvedToken(id, d, quality, vocabulary.idf(id)))
            }
        }
        out.sortWith(compareBy({ it.distance }, { it.tokenId }))
        return out
    }

    private fun resolveV2Uncached(queryToken: String): List<ResolvedToken> {
        val exact = vocabulary.idOf(queryToken)
        if (exact >= 0) {
            lastTypoScan = IntRange.EMPTY
            return listOf(ResolvedToken(exact, 0, 1.0, vocabulary.idf(exact), MatchKind.EXACT))
        }

        val qLen = queryToken.length
        val byId = HashMap<Int, ResolvedToken>()

        // Typo: only the buckets within the ladder's budget — a 4-letter token never touches length 7.
        val budget = EditBudget.of(qLen)
        lastTypoScan = if (budget == 0) IntRange.EMPTY else (qLen - budget)..(qLen + budget)
        for (len in lastTypoScan) {
            val bucket = vocabulary.lengthBuckets[len] ?: continue
            for (id in bucket) {
                // Same cutoff trick as v1: a return of budget+1 means "further than the budget".
                val d = levenshtein.distance(queryToken, vocabulary.tokens[id], budget + 1).toInt()
                if (d < 1 || d > budget) continue
                byId[id] = ResolvedToken(id, d, 1.0 - TYPO_STEP * d, vocabulary.idf(id), MatchKind.TYPO)
            }
        }

        // Prefix: the vocabulary is sorted, so every token starting with t is one contiguous run
        // beginning at t's insertion point.
        if (qLen >= MIN_PREFIX_LENGTH) {
            var id = insertionPoint(queryToken)
            while (id < vocabulary.size && vocabulary.tokens[id].startsWith(queryToken)) {
                val c = vocabulary.tokens[id]
                if (c.length > qLen) {
                    val q = maxOf(PREFIX_FLOOR, qLen.toDouble() / c.length)
                    val prior = byId[id]
                    if (prior == null || q > prior.quality) {
                        // A prefix's edit distance to the whole token is the missing tail.
                        byId[id] = ResolvedToken(id, c.length - qLen, q, vocabulary.idf(id), MatchKind.PREFIX)
                    }
                }
                id++
            }
        }

        return byId.values.sortedWith(compareBy({ -it.quality }, { it.tokenId }))
    }

    /** Index of the first vocabulary token `≥` [token] in the vocabulary's (String natural) order. */
    private fun insertionPoint(token: String): Int {
        val i = vocabulary.tokens.binarySearch(token)
        return if (i >= 0) i else -(i + 1)
    }

    companion object {
        const val MAX_EDIT_DISTANCE = 2

        /** v2 typo quality step: `q = 1 − 0.15·d` (contracts §4.2). */
        const val TYPO_STEP = 0.15

        /** v2 prefix quality floor (contracts §4.2). */
        const val PREFIX_FLOOR = 0.80

        /** A prefix hit needs at least this many query characters (contracts §4.2). */
        const val MIN_PREFIX_LENGTH = 3
    }
}
