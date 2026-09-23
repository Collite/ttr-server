// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import kotlin.math.ln

/**
 * FZ-P2 — the interned token vocabulary for the index-first retrieval path.
 *
 * Where [TokenIndex] keys everything by string, this interns the distinct token vocabulary to `Int`
 * ids so the hot retrieval path is pure primitive-array arithmetic — no string comparisons or
 * allocations. The vocabulary is the distinct set of folded surface∪lemma tokens across the corpus;
 * it is rebuilt on refresh exactly like [TokenIndex] (the legacy path keeps using [TokenIndex],
 * untouched).
 *
 * Determinism: the vocabulary is the *sorted* distinct token list, so a token's id is stable across
 * rebuilds of the same corpus — which keeps the parity goldens stable.
 *
 * IDF matches [TokenIndex] exactly (`ln((N+1)/(df+1)) + 1`, df from the surface∪lemma postings), so
 * the index-first approximate score and the legacy scorer agree on token weights.
 *
 * @property candidates the corpus in ordinal order — a token's postings are indices into this list.
 */
class TokenVocabulary(
    val candidates: List<Candidate>,
) {
    /** Distinct folded tokens, sorted (⇒ stable ids). Token id = index into this array. */
    val tokens: Array<String>

    private val idByToken: HashMap<String, Int>

    /** Number of candidates ("documents"). */
    val documentCount: Int = candidates.size

    /** IDF per token id — `ln((N+1)/(df+1)) + 1`, df = postings length (surface∪lemma). */
    val idf: DoubleArray

    /** IDF for a token absent from the corpus (maximally rare) — matches [TokenIndex.idfForAbsent]. */
    val idfAbsent: Double = ln(documentCount + 1.0) + 1.0

    /** Candidate ordinals containing each token id (either axis), ascending. */
    val postings: Array<IntArray>

    /** Per candidate ordinal: its surface token ids, in token order (the order bonus needs order). */
    val candidateTokenIds: Array<IntArray>

    /** Per candidate ordinal: its lemma token ids, in token order. */
    val candidateLemmaTokenIds: Array<IntArray>

    /** Token ids grouped by token length — the resolver scans only length-adjacent buckets. */
    val lengthBuckets: Map<Int, IntArray>

    /** Distinct token count. */
    val size: Int get() = tokens.size

    init {
        // 1. Distinct sorted vocabulary (surface ∪ lemma) ⇒ stable ids.
        val distinct = sortedSetOf<String>()
        for (c in candidates) distinct.addAll(c.allTokenSet)
        tokens = distinct.toTypedArray()

        idByToken = HashMap(tokens.size * 2)
        for (i in tokens.indices) idByToken[tokens[i]] = i

        // 2. Postings (candidate ordinals per token id, ascending by construction) + df.
        val postingLists = Array(tokens.size) { mutableListOf<Int>() }
        for (ordinal in candidates.indices) {
            // allTokenSet dedups within a candidate, so each candidate appears at most once per token.
            for (token in candidates[ordinal].allTokenSet) {
                postingLists[idByToken.getValue(token)].add(ordinal)
            }
        }
        postings = Array(tokens.size) { postingLists[it].toIntArray() }

        // 3. IDF from df (postings length), identical to TokenIndex.
        idf =
            DoubleArray(tokens.size) { id ->
                ln((documentCount + 1.0) / (postings[id].size + 1.0)) + 1.0
            }

        // 4. Per-candidate ordered token-id arrays (surface + lemma), for the order-aware rescore/future use.
        candidateTokenIds = Array(candidates.size) { ord -> candidates[ord].tokens.map { idOf(it) }.toIntArray() }
        candidateLemmaTokenIds =
            Array(candidates.size) { ord -> candidates[ord].lemmaTokens.map { idOf(it) }.toIntArray() }

        // 5. Length buckets for the resolver's cutoff-bounded ED≤2 scan.
        lengthBuckets =
            tokens.indices
                .groupBy { tokens[it].length }
                .mapValues { (_, ids) -> ids.toIntArray() }
    }

    /** Token id for [token] (must be already folded), or -1 if absent from the vocabulary. */
    fun idOf(token: String): Int = idByToken[token] ?: -1

    /** IDF weight for token id [id] (in `0 until size`). */
    fun idf(id: Int): Double = idf[id]

    /**
     * LP-P0 (contracts §4.3) — the weight of a query token [token] (folded): its IDF when it is in
     * the vocabulary, else [idfAbsent] (maximally rare). v2 weighs an unmatched query token by this.
     */
    fun idfOrMax(token: String): Double {
        val id = idOf(token)
        return if (id >= 0) idf[id] else idfAbsent
    }

    /** Candidate ordinals containing token id [id] (ascending). */
    fun postings(id: Int): IntArray = postings[id]
}
