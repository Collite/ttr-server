// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import kotlin.math.ln

class TokenIndex(
    private val candidates: List<Candidate>,
) {
    private val exactIndex: Map<String, List<String>> = buildExactIndex()
    private val idIndex: Map<String, Candidate> = candidates.associateBy { it.id }

    /** Number of candidates ("documents") in this (per-category) index. */
    val documentCount: Int = candidates.size

    // Inverse document frequency per token, derived from the same exact-token
    // index. `exactIndex[t].size` is the document frequency of `t`; with N
    // documents we use the smoothed, floored form
    //     idf(t) = ln((N + 1) / (df(t) + 1)) + 1
    // which is always ≥ 1 (no zero weights, no div-by-zero) and strictly
    // decreasing in df: a token in every candidate ≈ 1, a near-unique token is
    // several times larger. The matcher (TATRMAN) weights each token match by
    // this so matching a common token counts for little and matching a rare,
    // identifying one counts for a lot (GH #69).
    private val idfByToken: Map<String, Double> =
        exactIndex.mapValues { (_, ids) -> ln((documentCount + 1.0) / (ids.size + 1.0)) + 1.0 }

    // A token absent from the corpus is treated as maximally rare. (The matcher
    // only ever looks up candidate tokens, which are always present; this is a
    // defensive default.)
    private val idfForAbsent: Double = ln(documentCount + 1.0) + 1.0

    /**
     * IDF weight for [token] in this category — see [idfByToken]. FZ-P1: [token] MUST be already
     * folded (as every matcher call site passes it); the index keys are folded, so the former
     * per-lookup `.lowercase()` was a redundant allocation on the hot scoring path.
     */
    fun idf(token: String): Double = idfByToken[token] ?: idfForAbsent

    // LP-P0 — Σ idf over a candidate's tokens, per axis: the denominator of v2's coverage C.
    // Memoised lazily per candidate id (the index is rebuilt on refresh, so the memo goes with it);
    // v1 never calls it. Without it v2 paid one idf lookup per candidate token per request.
    private val surfaceIdfTotals = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val lemmaIdfTotals = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** Σ [idf] over [candidate]'s surface tokens ([lemma] = false) or lemma tokens ([lemma] = true). */
    fun idfTotal(
        candidate: Candidate,
        lemma: Boolean,
    ): Double {
        val memo = if (lemma) lemmaIdfTotals else surfaceIdfTotals
        return memo.getOrPut(candidate.id) {
            (if (lemma) candidate.lemmaTokens else candidate.tokens).sumOf { idf(it) }
        }
    }

    private fun buildExactIndex(): Map<String, List<String>> {
        val index = mutableMapOf<String, MutableList<String>>()
        for (candidate in candidates) {
            // Index by surface ∪ lemma tokens so a query token matches either axis.
            for (token in candidate.allTokenSet) {
                index.getOrPut(token.lowercase()) { mutableListOf() }.add(candidate.id)
            }
        }
        return index
    }

    fun findCandidatesWithExactToken(token: String): List<String> = exactIndex[token.lowercase()] ?: emptyList()

    fun findCandidatesWithAnyToken(tokens: List<String>): Set<String> {
        val result = mutableSetOf<String>()
        for (token in tokens) {
            result.addAll(findCandidatesWithExactToken(token))
        }
        return result
    }

    fun getCandidateById(id: String): Candidate? = idIndex[id]

    fun getAllCandidateIds(): List<String> = candidates.map { it.id }
}
